package tantalum.shell.boot;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.kernel.Kernel;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.table.BuiltinSteps;
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
import tantalum.shell.journal.Journal;
import tantalum.shell.journal.StoreLock;
import tantalum.shell.journal.TurnQuery;
import tantalum.shell.loop.Cleanroom;
import tantalum.shell.loop.Dispatcher;
import tantalum.shell.loop.InFlightCommands;
import tantalum.shell.loop.InputRing;
import tantalum.shell.loop.JournalWriter;
import tantalum.shell.loop.OutputRing;
import tantalum.shell.loop.ResultPublisher;
import tantalum.shell.loop.StateView;
import tantalum.shell.loop.SubmissionLedger;
import tantalum.shell.model.ModelClient;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.sse.SseHub;
import tantalum.shell.tool.GrepTool;
import tantalum.shell.tool.ReadFileTool;
import tantalum.shell.tool.ToolCatalog;
import tantalum.shell.tool.Workspaces;
import tantalum.shell.web.ExternalGate;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.DoWork;
import tantalum.value.command.RunTool;
import tantalum.value.command.StartTimer;
import tantalum.value.event.Cancel;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.SessionState;

/** 把环、journal、Handler 和插件装配起来，并提供外部提交的入口。不依赖 Spring。 */
public final class Engine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Engine.class);
    private static final Duration DRAIN = Duration.ofSeconds(5);

    /** 提交的结果。{@code status} 对应 HTTP 状态。 */
    public record Submission(int status, SubmissionId submission, boolean duplicate) {}

    private final Clock clock;
    private final StoreLock lock;
    private final Journal journal;
    private final EventCodec codec;
    private final JournalWriter writer;
    private final InputRing input;
    private final OutputRing output;
    private final StateView view;
    private final Transcript transcript;
    private final SubmissionLedger ledger = new SubmissionLedger();
    private final ExternalGate gate;
    private final SseHub hub;
    private final TurnQuery turns;
    private final Set<SubmissionId> newRunSubmissions = ConcurrentHashMap.newKeySet();

    /** 构造失败时释放已拿到的锁和 journal，否则嵌入方重试会一直撞上“store is locked”。 */
    public Engine(TantalumProperties properties, ModelClient model, Duration modelWait, int modelConcurrency,
                  List<Tool> tools, List<KernelHook> hooks, List<WorkType> workTypes, List<AfterToolHook> afterHooks,
                  EventCodec codec, Clock clock) throws IOException {
        this.clock = clock;
        this.codec = codec;
        this.lock = StoreLock.acquire(properties.dataDir());
        try {
            this.journal = Journal.open(properties.dataDir(), codec);
        } catch (IOException | RuntimeException failure) {
            closeQuietly(lock);
            throw failure;
        }
        try {
            Assembly parts = assemble(properties, model, modelWait, modelConcurrency, tools, hooks, workTypes, afterHooks);
            this.view = parts.view;
            this.transcript = parts.transcript;
            this.turns = parts.turns;
            this.hub = parts.hub;
            this.writer = parts.writer;
            this.gate = parts.gate;
            this.input = parts.input;
            this.output = parts.output;
        } catch (RuntimeException failure) {
            closeQuietly(journal);
            closeQuietly(lock);
            throw failure;
        }
        output.start();
        input.start();
        LOG.info("store ready at {}, next seq {}", properties.dataDir(), writer.nextSeq());
    }

    private record Assembly(StateView view, Transcript transcript, TurnQuery turns, SseHub hub, JournalWriter writer,
                            ExternalGate gate, InputRing input, OutputRing output) {}

    private Assembly assemble(TantalumProperties properties, ModelClient model, Duration modelWait, int modelConcurrency,
                              List<Tool> tools, List<KernelHook> hooks, List<WorkType> workTypes,
                              List<AfterToolHook> afterHooks) {
        Loop loop = properties.loop();
        ToolCatalog catalog = new ToolCatalog();
        Workspaces workspaces = properties.workspaceMode().equals("shared")
                ? Workspaces.shared(properties.workspace()) : Workspaces.perUser(properties.workspace());
        if (workspaces.isShared()) {
            LOG.warn("tantalum.workspace-mode=shared: all users share {} and can read each other's files.", properties.workspace());
        }
        catalog.register(new ReadFileTool());
        catalog.register(new GrepTool());
        tools.forEach(catalog::register);

        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        Kernel kernel = new Kernel(table, new HookChain(hooks), catalog.safety(), loop.maxParallelTools());

        Startup.Recovered recovered = Startup.recover(journal, kernel, ledger);
        StateView view = new StateView(recovered.state());
        TurnQuery turns = new TurnQuery(journal, kernel, codec.mapper());
        SseHub hub = new SseHub(recovered.nextSeq(), 1024, 4096, turns::messagesAfter, codec.mapper());
        JournalWriter writer = new JournalWriter(journal, recovered.nextSeq());

        InFlightCommands inFlight = new InFlightCommands();
        InputRing input = new InputRing(loop.inputRingSize());
        ResultPublisher results = new ResultPublisher(input, inFlight, clock, loop.internalReserve());
        Map<Class<? extends tantalum.value.command.Command>, Handler> handlers = Map.of(
                AssembleContext.class, new ContextHandler(workspaces, properties.systemPrompt(), catalog),
                CallModel.class, new ModelHandler(model, new CallLimiter(modelConcurrency), modelWait, hub::onChunk),
                RunTool.class, new ToolHandler(catalog, workspaces, afterHooks, properties.toolTimeout()),
                DoWork.class, new WorkHandler(workspaces, workTypes, properties.toolTimeout()),
                StartTimer.class, new TimerHandler());
        OutputRing output = new OutputRing(loop.outputRingSize(), new Dispatcher(handlers, inFlight, results));
        Cleanroom cleanroom = new Cleanroom(kernel, recovered.state(), writer::halted, output::tryPublish,
                recovered.transcript(), hub, view, results);
        // journal 写不进去、内核之外抛出、任一环的消费者出错，入口都返回 503，而不是让提交静默消失。
        BooleanSupplier halted = () -> writer.halted() || cleanroom.broken() || input.broken() || output.broken();
        ExternalGate gate = new ExternalGate(loop, () -> (int) view.current().activeRunCount(), halted);
        input.connect(writer, (slot, sequence, endOfBatch) -> {
            EventEnvelope envelope = slot.envelope;
            cleanroom.onEvent(slot, sequence, endOfBatch);
            releaseIfExternal(envelope);
        });
        return new Assembly(view, recovered.transcript(), turns, hub, writer, gate, input, output);
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception failure) {
            LOG.warn("cleanup after failed start: {}", failure.toString());
        }
    }

    private void releaseIfExternal(EventEnvelope envelope) {
        Event event = envelope.event();
        SubmissionId submission = switch (event) {
            case UserPrompt prompt -> prompt.submission();
            case SteerSubmitted steer -> steer.submission();
            case FollowUpSubmitted followUp -> followUp.submission();
            case Cancel cancel -> null;
            default -> {
                yield null;
            }
        };
        boolean external = submission != null || event instanceof Cancel;
        if (external) {
            gate.release(envelope.principal());
        }
        if (submission != null && newRunSubmissions.remove(submission)) {
            gate.releaseNewRun();
        }
    }

    public Submission submit(Principal principal, SessionId session, RequestId request, String mode, String text) {
        KernelState state = view.current();
        SessionState existing = state.sessions().get(session);
        if (existing != null && !existing.principal().equals(principal)) {
            return new Submission(404, null, false);
        }
        SubmissionLedger.Claim claim = ledger.claim(principal, request);
        if (!claim.isNew()) {
            return new Submission(200, claim.submission(), true);
        }
        boolean needsNewRun = existing == null || existing.activeRun() == null;
        ExternalGate.Verdict verdict = gate.admit(principal, needsNewRun);
        if (verdict != ExternalGate.Verdict.ADMITTED) {
            ledger.release(principal, request);
            return new Submission(verdict == ExternalGate.Verdict.HALTED ? 503 : 429, null, false);
        }
        if (needsNewRun) {
            newRunSubmissions.add(claim.submission());
        }
        Event event = switch (mode) {
            case "steer" -> new SteerSubmitted(claim.submission(), request, text);
            case "follow-up" -> new FollowUpSubmitted(claim.submission(), request, text);
            default -> new UserPrompt(claim.submission(), request, new OwnerRef.OfSession(session), text);
        };
        if (!input.tryPublish(new EventEnvelope(EventEnvelope.UNASSIGNED, clock.instant(), principal, session, null, event))) {
            gate.release(principal);
            if (newRunSubmissions.remove(claim.submission())) {
                gate.releaseNewRun();
            }
            ledger.release(principal, request);
            return new Submission(429, null, false);
        }
        return new Submission(200, claim.submission(), false);
    }

    /** 返回 HTTP 状态。 */
    public int cancel(Principal principal, RunRef run) {
        ExternalGate.Verdict verdict = gate.admit(principal, false);
        if (verdict != ExternalGate.Verdict.ADMITTED) {
            return verdict == ExternalGate.Verdict.HALTED ? 503 : 429;
        }
        Event event = new Cancel(new RequestId(UUID.randomUUID().toString()));
        if (!input.tryPublish(new EventEnvelope(EventEnvelope.UNASSIGNED, clock.instant(), principal, run.session(), run.run(), event))) {
            gate.release(principal);
            return 429;
        }
        return 200;
    }

    public KernelState state() {
        return view.current();
    }

    public SseHub hub() {
        return hub;
    }

    public Transcript transcript() {
        return transcript;
    }

    public TurnQuery turns() {
        return turns;
    }

    public EventCodec codec() {
        return codec;
    }

    public Optional<tantalum.value.state.RunState> run(Principal principal, tantalum.value.id.RunId id) {
        return Optional.ofNullable(view.current().runs().get(id)).filter(run -> run.ref().principal().equals(principal));
    }

    /** 入口先关（{@code halted} 之外由 Web 层停止接收），再排空两个环，最后关 journal 和锁；每一步失败都不跳过后面的。 */
    @Override
    public void close() throws IOException, SQLException {
        Exception first = null;
        for (AutoCloseable step : List.<AutoCloseable>of(
                () -> input.shutdown(DRAIN), () -> output.shutdown(DRAIN), journal, lock)) {
            try {
                step.close();
            } catch (Exception failure) {
                LOG.error("engine close step failed", failure);
                first = first == null ? failure : first;
            }
        }
        switch (first) {
            case null -> { }
            case IOException io -> throw io;
            case SQLException sql -> throw sql;
            case RuntimeException runtime -> throw runtime;
            default -> throw new IllegalStateException(first);
        }
    }
}
