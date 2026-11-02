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
import tantalum.kernel.compaction.Compactor;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.table.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.boot.TantalumProperties.Loop;
import tantalum.shell.conversation.SessionSummary;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.handler.CallLimiter;
import tantalum.shell.handler.ContextHandler;
import tantalum.shell.handler.Handler;
import tantalum.shell.handler.ModelHandler;
import tantalum.shell.handler.TimerHandler;
import tantalum.shell.handler.ToolHandler;
import tantalum.shell.handler.WorkHandler;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.StoreLock;
import tantalum.shell.journal.TurnQuery;
import tantalum.shell.loop.Cleanroom;
import tantalum.shell.loop.CleanroomRing;
import tantalum.shell.loop.Dispatcher;
import tantalum.shell.loop.Evictor;
import tantalum.shell.loop.InFlightCommands;
import tantalum.shell.loop.InputRing;
import tantalum.shell.loop.JournalDispatcher;
import tantalum.shell.loop.OutputRing;
import tantalum.shell.loop.Preload;
import tantalum.shell.loop.Residency;
import tantalum.shell.loop.ResultPublisher;
import tantalum.shell.loop.StateView;
import tantalum.shell.loop.SubmissionLedger;
import tantalum.shell.loop.TailSequence;
import tantalum.shell.loop.UserLane;
import tantalum.shell.mcp.McpRegistry;
import tantalum.shell.mcp.McpSettings;
import tantalum.shell.memory.KnowledgeBase;
import tantalum.shell.memory.MemoryLimits;
import tantalum.shell.memory.MemoryStores;
import tantalum.shell.memory.MemoryTools;
import tantalum.shell.model.ModelClient;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.sse.SseHub;
import tantalum.shell.store.ColdReads;
import tantalum.shell.store.IndexRecovery;
import tantalum.shell.store.IndexWriter;
import tantalum.shell.store.SessionFileReader;
import tantalum.shell.store.SessionFiles;
import tantalum.shell.store.SessionIndex;
import tantalum.shell.store.SessionPaths;
import tantalum.shell.store.SessionReplay;
import tantalum.shell.store.SessionStore;
import tantalum.shell.store.SnapshotStore;
import tantalum.shell.store.SnapshotWriter;
import tantalum.shell.store.StoreFormat;
import tantalum.shell.store.UsageLedger;
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
import tantalum.value.event.CompactRequested;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.RunInterrupted;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.PrincipalState;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** 把环、journal、Handler 和插件装配起来，并提供外部提交的入口。不依赖 Spring。 */
public final class Engine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Engine.class);
    private static final Duration DRAIN = Duration.ofSeconds(5);
    private static final Duration INTERRUPT_RETRY = Duration.ofMillis(10);
    private static final int MAX_OPEN_FILES = 256;
    /** 只供回归测试：让序号跳号运行，证明没有代码依赖序号连续。 */
    private static final String SEQUENCE_STEP = "tantalum.test.sequence-step";

    /** 提交的结果。{@code status} 对应 HTTP 状态。 */
    public record Submission(int status, SubmissionId submission, boolean duplicate) {}

    /** 一个会话的只读视图：在内存的取当前状态，不在的重放文件得到，不进常驻。 */
    public record SessionView(SessionState state, List<RunState> runs, Transcript.Snapshot transcript) {}

    /** 会话文件读不了（中间损坏、索引库不可用）。 */
    public static final class SessionUnreadable extends RuntimeException {
        SessionUnreadable(SessionId session, Exception cause) {
            super("cannot read session " + session.value(), cause);
        }
    }

    private final Clock clock;
    private final StoreLock lock;
    private final SessionFiles files;
    private final EventCodec codec;
    private final JournalDispatcher dispatcher;
    private final InputRing input;
    private final CleanroomRing cleanroom;
    private final OutputRing output;
    private final StateView view;
    private final Transcript transcript;
    private final SessionIndex index;
    private final IndexWriter indexWriter;
    private final SubmissionLedger ledger;
    private final Residency residency = new Residency();
    private final SnapshotWriter snapshots;
    private final ExternalGate gate;
    private final SseHub hub;
    private final MemoryStores memories;
    private final McpRegistry mcp;
    private final TurnQuery turns;
    private final ColdReads cold;
    private final Set<SubmissionId> newRunSubmissions = ConcurrentHashMap.newKeySet();

    /** 构造失败时释放已拿到的锁和 journal，否则嵌入方重试会一直撞上“store is locked”。 */
    public Engine(TantalumProperties properties, ModelClient model, Duration modelWait, int modelConcurrency,
                  List<Tool> tools, List<KernelHook> hooks, List<WorkType> workTypes, List<AfterToolHook> afterHooks,
                  EventCodec codec, Clock clock) throws IOException {
        this.clock = clock;
        this.codec = codec;
        this.lock = StoreLock.acquire(properties.dataDir());
        try {
            StoreFormat.check(properties.dataDir());
            this.index = SessionIndex.open(properties.dataDir());
        } catch (IOException | RuntimeException failure) {
            closeQuietly(lock);
            throw failure;
        } catch (SQLException failure) {
            closeQuietly(lock);
            throw new IOException("cannot open index", failure);
        }
        this.ledger = new SubmissionLedger(this::findSubmission, SubmissionLedger.DEFAULT_CAPACITY);
        TantalumProperties.Index throttle = properties.index() == null ? TantalumProperties.Index.defaults() : properties.index();
        this.indexWriter = new IndexWriter(index, throttle.flushEvents(), throttle.flushInterval());
        SessionPaths paths = new SessionPaths(properties.dataDir());
        this.files = new SessionFiles(paths, codec, MAX_OPEN_FILES, indexWriter::onDurable);
        this.snapshots = new SnapshotWriter(new SnapshotStore(paths, codec), paths);
        List<RunState> unfinished;
        try {
            Assembly parts = assemble(properties, model, modelWait, modelConcurrency, tools, hooks, workTypes, afterHooks);
            this.view = parts.view;
            this.transcript = parts.transcript;
            this.turns = parts.turns;
            this.cold = parts.cold;
            this.hub = parts.hub;
            this.memories = parts.memories;
            this.mcp = parts.mcp;
            this.dispatcher = parts.dispatcher;
            this.cleanroom = parts.cleanroom;
            this.gate = parts.gate;
            this.input = parts.input;
            this.output = parts.output;
            unfinished = parts.unfinished;
        } catch (RuntimeException failure) {
            closeQuietly(snapshots);
            closeQuietly(files);
            closeQuietly(indexWriter);
            closeQuietly(index);
            closeQuietly(lock);
            throw failure;
        }
        output.start();
        cleanroom.start();
        input.start();
        interrupt(unfinished);
        LOG.info("store ready at {}", properties.dataDir());
    }

    private record Assembly(StateView view, Transcript transcript, TurnQuery turns, SseHub hub, JournalDispatcher dispatcher,
                            ExternalGate gate, InputRing input, CleanroomRing cleanroom, OutputRing output, MemoryStores memories, McpRegistry mcp,
                            List<RunState> unfinished, ColdReads cold) {}

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
        MemoryStores memories = new MemoryStores(workspaces, properties.dataDir(), MemoryLimits.of(properties.memory()), clock);
        KnowledgeBase knowledge = new KnowledgeBase(properties.knowledge().path(), properties.knowledge().maxBytes());
        MemoryTools.all(memories).forEach(catalog::register);
        tools.forEach(catalog::register);
        McpRegistry mcp = new McpRegistry(McpSettings.from(properties.mcp(), properties.toolTimeout(), System::getenv));
        catalog.setSource(mcp);

        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        Kernel kernel = new Kernel(table, new HookChain(hooks), catalog.safety(), loop.maxParallelTools());

        SessionStore store = new SessionStore(properties.dataDir(), codec);
        SessionPaths paths = new SessionPaths(properties.dataDir());
        UsageLedger usage = new UsageLedger(index, indexWriter);
        SessionReplay replay = new SessionReplay(paths, new SessionFileReader(codec), new SnapshotStore(paths, codec), usage, kernel);
        Startup.Recovered recovered = recover(properties, kernel, store, replay);
        StateView view = new StateView(recovered.state());
        TurnQuery turns = new TurnQuery(store, kernel, codec.mapper());
        SseHub hub = new SseHub(1024, 4096, turns::messagesAfter, codec.mapper());
        CleanroomRing cleanroomRing = new CleanroomRing(loop.inputRingSize());
        JournalDispatcher dispatcher = new JournalDispatcher(loop.inputRingSize(), new TailSequence(paths, Integer.getInteger(SEQUENCE_STEP, 1)),
                files::append, cleanroomRing::tryPublish, loader(replay, usage), residency);

        InFlightCommands inFlight = new InFlightCommands();
        InputRing input = new InputRing(loop.inputRingSize());
        ResultPublisher results = new ResultPublisher(input, inFlight, clock, loop.internalReserve());
        Map<Class<? extends tantalum.value.command.Command>, Handler> handlers = Map.of(
                AssembleContext.class, new ContextHandler(workspaces, properties.systemPrompt(), catalog, memories, knowledge, mcp.fixedNote(),
                        properties.compactionSettings()),
                CallModel.class, new ModelHandler(model, new CallLimiter(modelConcurrency), modelWait, hub::onChunk),
                RunTool.class, new ToolHandler(catalog, workspaces, afterHooks, properties.toolTimeout()),
                DoWork.class, new WorkHandler(workspaces, workTypes, properties.toolTimeout()),
                StartTimer.class, new TimerHandler());
        OutputRing output = new OutputRing(loop.outputRingSize(), new Dispatcher(handlers, inFlight, results));
        Evictor evictor = new Evictor(evictorSettings(properties), residency, recovered.transcript(), snapshots, clock);
        for (SessionId session : recovered.state().sessions().keySet()) {
            residency.resident(session);
            evictor.recovered(session, recovered.transcript().snapshot(session).seq());
        }
        recovered.failed().forEach(residency::fail);
        Cleanroom cleanroom = new Cleanroom(kernel, recovered.state(), dispatcher::halted, output::tryPublish,
                recovered.transcript(), hub, view, results, indexWriter::onStep, evictor);
        // journal 写不进去、内核之外抛出、任一环的消费者出错，入口都返回 503，而不是让提交静默消失。
        BooleanSupplier halted = () -> dispatcher.halted() || cleanroom.broken() || input.broken() || cleanroomRing.broken() || output.broken();
        ExternalGate gate = new ExternalGate(loop, () -> (int) view.current().activeRunCount(), halted);
        input.connect(dispatcher);
        cleanroomRing.connect((slot, sequence, endOfBatch) -> {
            EventEnvelope envelope = slot.envelope;
            cleanroom.onEvent(slot, sequence, endOfBatch);
            releaseIfExternal(envelope);
            dispatcher.released();
        });
        return new Assembly(view, recovered.transcript(), turns, hub, dispatcher, gate, input, cleanroomRing, output, memories, mcp, recovered.unfinished(),
                new ColdReads(paths, replay));
    }

    private static Evictor.Settings evictorSettings(TantalumProperties properties) {
        TantalumProperties.Residency settings = properties.residency() == null ? TantalumProperties.Residency.defaults() : properties.residency();
        return new Evictor.Settings(settings.idle(), settings.maxResident(), settings.snapshotEvents(), settings.sweepInterval());
    }

    /** 重启前没结束的运行在开门前收尾，等它们走完管线，这样第一个请求看到的就是结束后的状态。 */
    private void interrupt(List<RunState> unfinished) {
        if (unfinished.isEmpty()) {
            return;
        }
        try {
            for (RunState run : unfinished) {
                EventEnvelope envelope = new EventEnvelope(EventEnvelope.UNASSIGNED, clock.instant(), run.ref().principal(),
                        run.ref().session(), run.ref().run(), new RunInterrupted());
                while (!input.tryPublish(envelope)) {
                    Thread.sleep(INTERRUPT_RETRY.toMillis());
                }
            }
            input.awaitDrained(DRAIN);
            if (!dispatcher.awaitIdle(DRAIN)) {
                LOG.warn("{} unfinished runs were not closed within {}", unfinished.size(), DRAIN);
            }
            LOG.info("closed {} runs left unfinished by the previous process", unfinished.size());
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        }
    }

    /** 平常只载入有未结束运行的会话；索引补不上时不知道哪些会话没结束，只能全量重放。 */
    private Startup.Recovered recover(TantalumProperties properties, Kernel kernel, SessionStore store, SessionReplay replay) {
        try {
            IndexRecovery.run(index, new SessionPaths(properties.dataDir()), new SessionFileReader(codec), kernel);
            return Startup.resume(index, replay);
        } catch (IOException | SQLException failure) {
            LOG.warn("index could not catch up with session files, replaying everything: {}", failure.toString());
            return Startup.recover(store, kernel);
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while starting", stopping);
        }
    }

    private static UserLane.Loader loader(SessionReplay replay, UsageLedger usage) {
        return new UserLane.Loader() {
            @Override
            public Optional<Preload> load(Principal principal, SessionId session, long beforeSeq) throws Exception {
                return replay.load(principal, session, beforeSeq).map(slice -> Preload.loaded(slice.snapshot().session(),
                        slice.snapshot().runs(), slice.snapshot().transcript(), slice.snapshot().seq(), slice.replayedEvents()));
            }

            @Override
            public PrincipalState usage(Principal principal, long beforeSeq) throws Exception {
                return new PrincipalState(principal, usage.before(principal, beforeSeq));
            }
        };
    }

    /** 查不到时当作新提交：可用性优先，日志留痕。 */
    private Optional<SubmissionId> findSubmission(Principal principal, RequestId request) {
        try {
            return index.submission(principal, request);
        } catch (SQLException failure) {
            LOG.warn("index lookup failed, treating request as new: {}", failure.toString());
            return Optional.empty();
        }
    }

    /** 新会话 ID 只属于第一个认领的用户。内存里已有的会话不用查，这里只走一次。 */
    private int claimSession(Principal principal, SessionId session) {
        try {
            return index.claim(session, principal) ? 200 : 404;
        } catch (SQLException failure) {
            LOG.error("cannot record session ownership", failure);
            return 503;
        }
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
            case CompactRequested compact -> compact.submission();
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
        if (residency.failure(session) != null) {
            return new Submission(500, null, false);
        }
        KernelState state = view.current();
        SessionState existing = state.sessions().get(session);
        if (existing != null && !existing.principal().equals(principal)) {
            return new Submission(404, null, false);
        }
        if (existing == null) {
            int owner = claimSession(principal, session);
            if (owner != 200) {
                return new Submission(owner, null, false);
            }
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

    /**
     * 手动压缩（context-compaction 决定 D4）。会话不存在或不是该用户的 → 404；正在运行、没有压缩设置或没有可压缩的内容 → 409。
     * 通过后发布 {@code CompactRequested}，内核在空闲会话上开一个特殊运行。{@code instructions} 可为 null。
     */
    public Submission compact(Principal principal, SessionId session, RequestId request, String instructions) {
        Optional<SessionView> found = session(principal, session);
        if (found.isEmpty()) {
            return new Submission(404, null, false);
        }
        SessionState existing = found.get().state();
        if (existing.activeRun() != null || existing.context() == null || existing.context().compaction() == null
                || !Compactor.worthCompacting(existing, existing.context().system(), existing.context().tools())) {
            return new Submission(409, null, false);
        }
        SubmissionLedger.Claim claim = ledger.claim(principal, request);
        if (!claim.isNew()) {
            return new Submission(200, claim.submission(), true);
        }
        ExternalGate.Verdict verdict = gate.admit(principal, true);
        if (verdict != ExternalGate.Verdict.ADMITTED) {
            ledger.release(principal, request);
            return new Submission(verdict == ExternalGate.Verdict.HALTED ? 503 : 429, null, false);
        }
        newRunSubmissions.add(claim.submission());
        Event event = new CompactRequested(claim.submission(), request, instructions);
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

    /** 某个工具当前是否对模型可见。 */
    public boolean catalogHas(String tool) {
        return mcp.find(tool).isPresent();
    }

    /** 每用户的长期记忆，接口和工具共用同一份实例。 */
    public MemoryStores memories() {
        return memories;
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

    /** 在内存的直接取，不在的从会话文件重放。别人的运行为空。 */
    public Optional<RunState> run(Principal principal, RunId id) {
        if (id.session().isEmpty()) {
            return Optional.empty();
        }
        RunState resident = view.current().runs().get(id);
        if (resident != null) {
            return Optional.of(resident).filter(run -> run.ref().principal().equals(principal));
        }
        return session(principal, id.session().get())
                .flatMap(found -> found.runs().stream().filter(run -> run.ref().run().equals(id)).findFirst());
    }

    /** 不存在或不是本人的会话为空。不在内存时只读本人目录下的文件，不进常驻。 */
    public Optional<SessionView> session(Principal principal, SessionId session) {
        KernelState state = view.current();
        SessionState resident = state.sessions().get(session);
        if (resident != null) {
            if (!resident.principal().equals(principal)) {
                return Optional.empty();
            }
            List<RunState> runs = state.runs().values().stream().filter(run -> run.ref().session().equals(session)).toList();
            return Optional.of(new SessionView(resident, runs, transcript.snapshot(session)));
        }
        try {
            return cold.read(principal, session).map(found -> new SessionView(found.session(), found.runs(),
                    new Transcript.Snapshot(found.transcript().lastSeq(), found.transcript().items())));
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            throw new SessionUnreadable(session, stopping);
        } catch (Exception failure) {
            throw new SessionUnreadable(session, failure);
        }
    }

    /** 侧栏的会话列表：读索引，在内存的会话用当前状态覆盖，因为索引可能还没写到最新。 */
    public List<SessionSummary> sessions(Principal principal) throws SQLException {
        KernelState state = view.current();
        Map<SessionId, SessionSummary> summaries = new java.util.HashMap<>();
        for (SessionIndex.Session row : index.sessions(principal)) {
            summaries.put(row.session(), new SessionSummary(row.session(), row.title(), row.lastActivity(), row.runStatus()));
        }
        for (SessionState session : state.sessions().values()) {
            if (session.principal().equals(principal)) {
                summaries.put(session.id(), SessionSummary.of(session.id(),
                        session.activeRun() == null ? null : state.runs().get(session.activeRun()), transcript.items(session.id())));
            }
        }
        return summaries.values().stream().sorted(java.util.Comparator.comparing(SessionSummary::lastActivity).reversed()).toList();
    }

    /** 入口先关（{@code halted} 之外由 Web 层停止接收），再依次排空输入环、用户通道和两个下游环，最后关文件和锁；每一步失败都不跳过后面的。 */
    @Override
    public void close() throws IOException {
        Exception first = null;
        for (AutoCloseable step : List.<AutoCloseable>of(
                mcp, () -> input.shutdown(DRAIN), () -> dispatcher.awaitIdle(DRAIN), () -> cleanroom.shutdown(DRAIN),
                () -> output.shutdown(DRAIN), snapshots, indexWriter, index, files, lock)) {
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
            case RuntimeException runtime -> throw runtime;
            default -> throw new IllegalStateException(first);
        }
    }
}
