package tantalum.shell.boot;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.kernel.Kernel;
import tantalum.kernel.Limits;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.boot.TantalumProperties.Loop;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.handler.CallLimiter;
import tantalum.shell.handler.ContextHandler;
import tantalum.shell.handler.Handler;
import tantalum.shell.handler.ModelHandler;
import tantalum.shell.handler.TimerHandler;
import tantalum.shell.handler.ToolHandler;
import tantalum.shell.handler.WorkHandler;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.TailSequence;
import tantalum.shell.loop.Cleanroom;
import tantalum.shell.loop.CleanroomRing;
import tantalum.shell.loop.Evictor;
import tantalum.shell.loop.ExternalGate;
import tantalum.shell.loop.HandlerDispatcher;
import tantalum.shell.loop.InFlightCommands;
import tantalum.shell.loop.InputRing;
import tantalum.shell.loop.Intake;
import tantalum.shell.loop.LaneDispatcher;
import tantalum.shell.loop.OutputRing;
import tantalum.shell.loop.Residency;
import tantalum.shell.loop.ResultPublisher;
import tantalum.shell.loop.StateView;
import tantalum.shell.loop.SubmissionLedger;
import tantalum.shell.mcp.McpRegistry;
import tantalum.shell.mcp.McpSettings;
import tantalum.shell.memory.KnowledgeBase;
import tantalum.shell.memory.MemoryStores;
import tantalum.shell.memory.MemoryTools;
import tantalum.shell.model.ModelClient;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.sse.SseHub;
import tantalum.shell.store.ColdReads;
import tantalum.shell.store.SessionIndex;
import tantalum.shell.store.SessionQueries;
import tantalum.shell.store.SessionReplay;
import tantalum.shell.store.TurnQuery;
import tantalum.shell.tool.GrepTool;
import tantalum.shell.tool.ReadFileTool;
import tantalum.shell.tool.ToolCatalog;
import tantalum.shell.tool.Workspaces;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.DoWork;
import tantalum.value.command.RunTool;
import tantalum.value.command.StartTimer;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.RunInterrupted;
import tantalum.value.state.KernelState;

/** 把存储、环、Handler 和插件装配起来，负责启动和关闭。提交走 {@link #intake()}，读取走 {@link #queries()}。不依赖 Spring。 */
public final class Engine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Engine.class);
    private static final Duration DRAIN = Duration.ofSeconds(5);
    private static final Duration INTERRUPT_RETRY = Duration.ofMillis(10);
    private static final Duration INTERRUPT_GRACE = Duration.ofSeconds(2);
    /** 只供跳号回归：让序号跳号运行，证明没有代码依赖序号连续。 */
    private static final String SEQUENCE_STEP = "tantalum.test.sequence-step";

    private final Clock clock;
    private final EventCodec codec;
    private final Duration shutdownWait;
    private final Storage storage;
    private final StateView view;
    private final SseHub hub;
    private final MemoryStores memories;
    private final McpRegistry mcp;
    private final InputRing input;
    private final LaneDispatcher lanes;
    private final CleanroomRing cleanroomRing;
    private final Cleanroom cleanroom;
    private final OutputRing output;
    private final HandlerDispatcher handlers;
    private final InFlightCommands inFlight;
    private final Intake intake;
    private final SessionQueries queries;
    /** 关闭开始后入口返回 503。 */
    private final AtomicBoolean closing = new AtomicBoolean();

    public Engine(TantalumProperties properties, ModelClient model, Duration modelWait, int modelConcurrency,
                  List<Tool> tools, List<KernelHook> hooks, List<WorkType> workTypes, List<AfterToolHook> afterHooks,
                  EventCodec codec, Clock clock) throws IOException {
        long started = System.nanoTime();
        this.clock = clock;
        this.codec = codec;
        this.shutdownWait = properties.shutdownWait();
        this.storage = Storage.open(properties.dataDir(), codec, properties.index());
        Loop loop = properties.loop();
        List<SessionIndex.Session> unfinished;
        try {
            ToolCatalog catalog = new ToolCatalog();
            Workspaces workspaces = properties.workspaceMode().equals("shared")
                    ? Workspaces.shared(properties.workspace()) : Workspaces.perUser(properties.workspace());
            if (workspaces.isShared()) {
                LOG.warn("tantalum.workspace-mode=shared: all users share {} and can read each other's files.", properties.workspace());
            }
            catalog.register(new ReadFileTool());
            catalog.register(new GrepTool());
            memories = new MemoryStores(workspaces, properties.dataDir(), properties.memory(), clock);
            MemoryTools.all(memories).forEach(catalog::register);
            tools.forEach(catalog::register);
            mcp = new McpRegistry(McpSettings.from(properties.mcp(), properties.toolTimeout(), System::getenv));
            catalog.setSource(mcp);

            TransitionTable table = new TransitionTable();
            BuiltinSteps.register(table);
            Kernel kernel = new Kernel(table, new HookChain(hooks), catalog.safety(),
                    new Limits(loop.maxParallelTools(), properties.hooks().maxWorkRounds()));
            SessionReplay replay = storage.replay(kernel);
            unfinished = storage.catchUp(kernel);

            Residency residency = new Residency();
            Transcript transcript = new Transcript();
            view = new StateView();
            TurnQuery turns = new TurnQuery(storage.paths, replay, codec.mapper());
            hub = new SseHub(1024, 4096, turns::messagesAfter, codec.mapper());
            queries = new SessionQueries(view, transcript, storage.index, new ColdReads(storage.paths, replay), turns);

            cleanroomRing = new CleanroomRing(loop.inputRingSize());
            lanes = new LaneDispatcher(loop.inputRingSize(), new TailSequence(storage.paths, Integer.getInteger(SEQUENCE_STEP, 1)),
                    storage.files::append, cleanroomRing::tryPublish, storage.loader(replay), residency);
            inFlight = new InFlightCommands();
            input = new InputRing(loop.inputRingSize());
            ResultPublisher results = new ResultPublisher(input, inFlight, clock, loop.internalReserve());
            Map<Class<? extends Command>, Handler> byCommand = Map.of(
                    AssembleContext.class, new ContextHandler(workspaces, properties.systemPrompt(), catalog, memories,
                            new KnowledgeBase(properties.knowledge().path(), properties.knowledge().maxBytes()), mcp.fixedNote(),
                            properties.compactionSettings()),
                    CallModel.class, new ModelHandler(model, new CallLimiter(modelConcurrency), modelWait, hub::chunks, clock),
                    RunTool.class, new ToolHandler(catalog, workspaces, afterHooks, properties.toolTimeout(), clock),
                    DoWork.class, new WorkHandler(workspaces, workTypes, properties.toolTimeout(), clock),
                    StartTimer.class, new TimerHandler());
            handlers = new HandlerDispatcher(byCommand, inFlight, results);
            output = new OutputRing(loop.outputRingSize(), handlers);
            Evictor evictor = new Evictor(properties.residency(), residency, transcript, clock);
            cleanroom = new Cleanroom(kernel, lanes::halted, output::tryPublish, transcript, hub, view, results,
                    storage.indexWriter::onStep, evictor);

            // 文件写不进去、内核之外抛出、任一环的消费者出错，入口都返回 503，而不是让提交静默消失。
            BooleanSupplier halted = () -> closing.get() || lanes.halted() || cleanroom.broken() || input.broken()
                    || cleanroomRing.broken() || output.broken();
            ExternalGate gate = new ExternalGate(new ExternalGate.Limits(loop.maxActiveRuns(), loop.maxUserInFlight(), loop.maxExternalInFlight()),
                    () -> (int) view.current().activeRunCount(), halted);
            SubmissionLedger ledger = new SubmissionLedger(storage::findSubmission, SubmissionLedger.DEFAULT_CAPACITY);
            intake = new Intake(input, gate, ledger, view, residency, storage::claim,
                    (principal, session) -> queries.session(principal, session).map(SessionQueries.SessionView::state), clock);
            input.connect(lanes);
            cleanroomRing.connect((slot, sequence, endOfBatch) -> {
                EventEnvelope envelope = slot.envelope;
                cleanroom.onEvent(slot, sequence, endOfBatch);
                intake.processed(envelope);
                lanes.released();
            });
        } catch (RuntimeException failure) {
            Storage.closeQuietly(storage);
            throw failure;
        }
        output.start();
        cleanroomRing.start();
        input.start();
        interrupt(unfinished);
        LOG.info("store ready at {} in {} ms: {} runs left unfinished by the previous process closed as interrupted",
                properties.dataDir(), Duration.ofNanos(System.nanoTime() - started).toMillis(), unfinished.size());
    }

    /** 提问、排队、插话、手动压缩和取消。 */
    public Intake intake() {
        return intake;
    }

    /** 会话、运行和回放的读取。 */
    public SessionQueries queries() {
        return queries;
    }

    public SseHub hub() {
        return hub;
    }

    /** 每用户的长期记忆，接口和工具共用同一份实例。 */
    public MemoryStores memories() {
        return memories;
    }

    public EventCodec codec() {
        return codec;
    }

    /** 内存里的内核状态，给诊断和测试用。 */
    public KernelState state() {
        return view.current();
    }

    /** 某个 MCP 工具当前是否对模型可见。 */
    public boolean mcpHas(String tool) {
        return mcp.find(tool).isPresent();
    }

    /**
     * 重启前没结束的运行在开门前收尾，等它们走完管线，这样第一个请求看到的就是结束后的状态。
     * 这些会话和平时一样由用户通道从文件载入；载入失败的会话按平时的规则拒绝，不拦住启动。
     */
    private void interrupt(List<SessionIndex.Session> unfinished) {
        if (unfinished.isEmpty()) {
            return;
        }
        try {
            for (SessionIndex.Session session : unfinished) {
                EventEnvelope envelope = new EventEnvelope(EventEnvelope.UNASSIGNED, clock.instant(), session.principal(),
                        session.session(), session.activeRun(), new RunInterrupted());
                while (!input.tryPublish(envelope)) {
                    Thread.sleep(INTERRUPT_RETRY.toMillis());
                }
            }
            input.awaitDrained(DRAIN);
            if (!lanes.awaitIdle(DRAIN)) {
                LOG.warn("{} unfinished runs were not closed within {}", unfinished.size(), DRAIN);
            }
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 关闭：入口返回 503 → 断开 SSE → 不再启动新的 Handler，等在途的最多 {@code shutdown-wait}，到时中断 →
     * 排空输入环、各用户通道和两个下游环 → 关 MCP 和存储。每一步失败都不跳过后面的。
     * 已返回 200 的提交在排空输入环时落盘；没走完的运行留在文件里，下次启动时以“因重启中断”收尾。
     */
    @Override
    public void close() throws IOException {
        Shutdown.run(List.of(
                new Shutdown.Step("refuse new submissions", () -> closing.set(true)),
                new Shutdown.Step("close event streams", hub::closeEveryone),
                new Shutdown.Step("stop starting handlers", handlers::close),
                new Shutdown.Step("wait for running handlers", this::awaitHandlers),
                new Shutdown.Step("drain input ring", () -> input.shutdown(DRAIN)),
                new Shutdown.Step("drain user lanes", () -> lanes.awaitIdle(DRAIN)),
                new Shutdown.Step("drain cleanroom ring", () -> cleanroomRing.shutdown(DRAIN)),
                new Shutdown.Step("drain output ring", () -> output.shutdown(DRAIN)),
                new Shutdown.Step("close mcp", mcp),
                new Shutdown.Step("close storage", storage)));
    }

    /** 到时中断；被中断的 Handler 的结果不写回，重启时按 H9 收尾。不响应中断的再等一小会儿就不管了。 */
    private void awaitHandlers() throws InterruptedException {
        if (inFlight.awaitEmpty(shutdownWait)) {
            return;
        }
        LOG.warn("{} handlers still running after {}; interrupting them", inFlight.size(), shutdownWait);
        inFlight.abortForShutdown();
        if (!inFlight.awaitEmpty(INTERRUPT_GRACE)) {
            LOG.warn("{} handlers ignored the interrupt; closing without them", inFlight.size());
        }
    }
}
