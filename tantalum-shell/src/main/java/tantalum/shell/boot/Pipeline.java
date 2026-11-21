package tantalum.shell.boot;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import tantalum.kernel.Kernel;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.attachment.RequestImages;
import tantalum.shell.attachment.StoredMedia;
import tantalum.shell.boot.TantalumProperties.Loop;
import tantalum.shell.handler.ContextHandler;
import tantalum.shell.handler.Handler;
import tantalum.shell.handler.ModelHandler;
import tantalum.shell.handler.TimerHandler;
import tantalum.shell.handler.ToolHandler;
import tantalum.shell.handler.WorkHandler;
import tantalum.shell.journal.TailSequence;
import tantalum.shell.knowledge.KnowledgeBase;
import tantalum.shell.loop.Cleanroom;
import tantalum.shell.loop.CleanroomRing;
import tantalum.shell.loop.DeadlineTimers;
import tantalum.shell.loop.Evictor;
import tantalum.shell.loop.ExternalGate;
import tantalum.shell.loop.FactListener;
import tantalum.shell.loop.HandlerDispatcher;
import tantalum.shell.loop.InFlightCommands;
import tantalum.shell.loop.InputRing;
import tantalum.shell.loop.Intake;
import tantalum.shell.loop.LaneDispatcher;
import tantalum.shell.loop.OutputRing;
import tantalum.shell.loop.Receipts;
import tantalum.shell.loop.Release;
import tantalum.shell.loop.Snapshotting;
import tantalum.shell.loop.Residency;
import tantalum.shell.loop.ResultPublisher;
import tantalum.shell.loop.SubmissionLedger;
import tantalum.shell.monitor.Probe;
import tantalum.shell.plugin.ContextContributor;
import tantalum.shell.store.SessionQueries;
import tantalum.shell.trace.TraceRecord;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.DoWork;
import tantalum.value.command.RunTool;
import tantalum.value.command.StartTimer;
import tantalum.value.decision.Fact;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionState;

/**
 * 装配第四步：管线——内核、输入环、用户通道、净室、输出环、处理器、入口。这里只建不起：
 * 环由 {@link Engine} 在装配完成后启动。
 */
record Pipeline(Residency residency, CleanroomRing cleanroomRing, LaneDispatcher lanes, InFlightCommands inFlight, InputRing input,
                DeadlineTimers deadlines, HandlerDispatcher handlers, OutputRing output, Cleanroom cleanroom, Intake intake) {

    /** 只供跳号回归：让序号跳号运行，证明没有代码依赖序号连续。 */
    private static final String SEQUENCE_STEP = "tantalum.test.sequence-step";

    /**
     * 按引用顺序把环和 Handler 接起来。{@code probe} 是实时监视的埋点（live-monitor），接到各个环节上；{@code contributors} 是插件给上下文的那一份（memory-plugin）。
     */
    static Pipeline open(TantalumProperties properties, Stores stores, Toolbox toolbox, Queries queries, Clock clock, Receipts receipts,
                         BooleanSupplier closing, List<KernelHook> hooks, List<AutoCloseable> opened, Probe probe,
                         List<ContextContributor> contributors) {
        Storage storage = stores.storage();
        Loop loop = properties.loop();
        Kernel kernel = KernelFactory.kernel(properties, toolbox.catalog().safety(), hooks);
        Residency residency = new Residency(clock, properties.residency().loadRetry());
        CleanroomRing cleanroomRing = new CleanroomRing(loop.inputRingSize());
        LaneDispatcher lanes = new LaneDispatcher(loop.inputRingSize(), loop.maxUserPermits(),
                new TailSequence(storage.paths, Integer.getInteger(SEQUENCE_STEP, 1)),
                storage.files::append, cleanroomRing::tryPublish, storage.loader(), residency);
        InFlightCommands inFlight = new InFlightCommands();
        InputRing input = new InputRing(loop.inputRingSize());
        DeadlineTimers deadlines = new DeadlineTimers(clock, input::tryPublish);
        opened.add(deadlines);
        ResultPublisher results = new ResultPublisher(input, inFlight, clock, loop.internalReserve());
        RequestImages requestImages = new RequestImages(storage.attachments);
        // 一个事件走完全程时归还外部名额和放行名额；入口在净室之后才建，所以经一个槽位取。交代的处理器也经它送
        Intake[] intakeSlot = new Intake[1];
        // 交代的三条命令都是往别的会话放输入，同一个处理器，经入口送
        Handler assignments = new tantalum.shell.handler.AssignmentHandler(new tantalum.shell.handler.AssignmentHandler.Deliveries() {
                    @Override
                    public int deliver(Principal principal, SessionId session, tantalum.value.event.SessionCreated create,
                                       tantalum.value.event.Input delivered) {
                        return intakeSlot[0].deliver(principal, session, create, delivered);
                    }

                    @Override
                    public int cancel(Principal principal, SessionId session, String assignment) {
                        return intakeSlot[0].cancel(principal, session, assignment);
                    }
                },
                new tantalum.shell.skill.SkillAgents(toolbox.localWorkspace(), toolbox.skillCatalog()),
                (user, session) -> queries.queries().session(user, session).map(found -> found.state().activeRun() != null).orElse(false));
        Map<Class<? extends Command>, Handler> byCommand = Map.of(
                AssembleContext.class, new ContextHandler(toolbox.localWorkspace(), properties.systemPrompt(), toolbox.catalog(), toolbox.gate(),
                        contributors, new KnowledgeBase(properties.knowledge().path(), properties.knowledge().maxBytes()),
                        toolbox.fixedNote(), toolbox.compaction(), toolbox.routes(), stores.userSettings(),
                        session -> dirOf(queries.view().current(), session), toolbox.skillCatalog())
                        .offRecord(session -> offRecordOf(queries.view().current(), session))
                        .sessions(session -> queries.view().current().sessions().get(session))
                        .mainWindowTokens(properties.mainWindowTokens())
                        .clock(clock)
                        .workspaces(new ContextHandler.Workspaces() {
                            private final java.util.concurrent.ConcurrentHashMap<tantalum.value.id.Principal, Known> workspaces =
                                    new java.util.concurrent.ConcurrentHashMap<>();

                            private record Known(long at, java.util.List<tantalum.shell.project.Project> projects) {}

                            @Override
                            public java.util.List<tantalum.shell.project.Project> list(tantalum.value.id.Principal user) {
                                Known cached = workspaces.get(user);
                                long now = System.nanoTime();
                                if (cached != null && now - cached.at() < 1_000_000_000L) {
                                    return cached.projects();
                                }
                                try {
                                    java.util.List<tantalum.shell.project.Project> projects = stores.projects().list(user);
                                    workspaces.put(user, new Known(now, projects));
                                    return projects;
                                } catch (java.sql.SQLException unreadable) {
                                    return java.util.List.of();
                                }
                            }

                            // 每次调模型都要这一段：只读索引库（不为每个在内存的会话重算一遍对话条目），同一个人一秒内用上一次的结果。
                            // 索引可能比内存晚一步，这一段本来就只写粗粒度的东西，够用
                            private final java.util.concurrent.ConcurrentHashMap<tantalum.value.id.Principal, Listed> recent =
                                    new java.util.concurrent.ConcurrentHashMap<>();

                            private record Listed(long at, java.util.List<tantalum.shell.handler.WorkspaceSection.Row> rows) {}

                            @Override
                            public java.util.List<tantalum.shell.handler.WorkspaceSection.Row> sessions(tantalum.value.id.Principal user) {
                                Listed cached = recent.get(user);
                                long now = System.nanoTime();
                                if (cached != null && now - cached.at() < 1_000_000_000L) {
                                    return cached.rows();
                                }
                                try {
                                    tantalum.value.state.KernelState state = queries.view().current();
                                    java.util.Set<tantalum.value.id.SessionId> archived = stores.marks().allArchived(user);
                                    java.util.List<tantalum.shell.handler.WorkspaceSection.Row> rows = storage.index.sessions(user).stream()
                                            .filter(row -> !row.offRecord() && !archived.contains(row.session()))
                                            .map(row -> new tantalum.shell.handler.WorkspaceSection.Row(new tantalum.shell.conversation.SessionSummary(
                                                    row.session(), row.shownTitle(), row.lastActivity(), row.runStatus(), row.dir(), false),
                                                    tokensOf(state, row.session()), row.kind())).toList();
                                    recent.put(user, new Listed(now, rows));
                                    return rows;
                                } catch (java.sql.SQLException unreadable) {
                                    return java.util.List.of();
                                }
                            }
                        }),
                CallModel.class, new ModelHandler(toolbox.routes(), queries.hub()::chunks, clock,
                        run -> new StoredMedia(storage.attachments, requestImages, run.principal(), run.session()),
                        run -> attended(queries.view().current(), run), properties.weights()),
                RunTool.class, new ToolHandler(toolbox.catalog(), stores.workspaces(), toolbox.gate(), properties.toolTimeout(), clock,
                        session -> dirOf(queries.view().current(), session), session -> offRecordOf(queries.view().current(), session)),
                DoWork.class, new WorkHandler(stores.workspaces(), toolbox.gate(), properties.toolTimeout(), clock),
                StartTimer.class, new TimerHandler(),
                tantalum.value.command.Assign.class, assignments, tantalum.value.command.ReturnReport.class, assignments,
                tantalum.value.command.StopAssignment.class, assignments);
        HandlerDispatcher handlers = new HandlerDispatcher(byCommand, inFlight, results);
        OutputRing output = new OutputRing(loop.outputRingSize(), handlers);
        Evictor evictor = new Evictor(properties.residency(), residency, queries.projections(), clock, storage.files::forget);
        // 处理完的一步由用户通道写成一行、落盘后才放行命令和推送，提交的回执也在这时完成；那之后才归还外部名额和放行名额
        Release saved = (envelope, record, snapshot, afterDurable, done) -> lanes.release(envelope, record, snapshot, () -> {
            afterDurable.run();
            receipts.durable(envelope);
        }, done);
        // 推送之外，运行表也收轨迹记录（run-tree 需求 3）
        FactListener facts = FactListener.both(queries.hub(), new FactListener() {
            @Override
            public void onFact(long seq, int index, Fact fact) {
            }

            @Override
            public void onRecords(long seq, Principal owner, List<TraceRecord> records) {
                storage.runs.onRecords(seq, owner, records);
            }
        });
        Cleanroom cleanroom = new Cleanroom(kernel, lanes::halted, output::tryPublish, queries.projections(), facts, queries.view(), results,
                storage.indexWriter::onStep, evictor, saved, new Snapshotting() {
                    @Override
                    public boolean wanted(Principal principal, SessionId session) {
                        return storage.files.takeWanted(principal, session);
                    }

                    @Override
                    public tantalum.shell.journal.Snapshot of(tantalum.shell.loop.SessionSlice slice) {
                        return storage.snapshots.of(slice);
                    }
                }, envelope -> processed(envelope, receipts, intakeSlot[0], lanes));
        // 文件写不进去、内核之外抛出、任一环的消费者出错，入口都返回 503，而不是让提交静默消失。
        BooleanSupplier halted = () -> closing.getAsBoolean() || lanes.halted() || cleanroom.broken() || input.broken()
                || cleanroomRing.broken() || output.broken();
        ExternalGate gate = new ExternalGate(new ExternalGate.Limits(loop.maxActiveRuns(), loop.maxUserInFlight(), loop.maxExternalInFlight()),
                () -> (int) queries.view().current().activeRunCount(), halted);
        SubmissionLedger ledger = new SubmissionLedger(storage::findSubmission, SubmissionLedger.DEFAULT_CAPACITY);
        Intake intake = new Intake(input, gate, ledger, queries.view(), residency, storage::claim,
                (principal, session) -> queries.queries().session(principal, session).map(SessionQueries.SessionView::state), storage::kind,
                clock, receipts);
        intakeSlot[0] = intake;
        input.watch(probe);
        lanes.watch(probe);
        cleanroom.watch(probe);
        handlers.watch(probe);
        toolbox.localLinks().watch(probe);
        input.connect(lanes);
        cleanroomRing.connect(cleanroom);
        lanes.onHalt(receipts::failAll);
        return new Pipeline(residency, cleanroomRing, lanes, inFlight, input, deadlines, handlers, output, cleanroom, intake);
    }

    /** 有没有人在等这一轮：只有你说的话开的那一轮算（缓存留多久看它）。运行不在内存时当作有人在等。 */
    static boolean attended(KernelState state, tantalum.value.id.RunRef run) {
        tantalum.value.state.RunState found = state.runs().get(run.run());
        return found == null || found.cause().attended();
    }

    /** 会话是不是“不进记忆”的（memory-final-plan 第 11 节）。 */
    static boolean offRecordOf(KernelState state, SessionId session) {
        SessionState found = state.sessions().get(session);
        return found != null && found.offRecord();
    }

    /** 会话的工作目录；会话不在内存（不会有它的命令在跑）或没有目录时为 null。 */
    static String dirOf(KernelState state, SessionId session) {
        SessionState found = state.sessions().get(session);
        return found == null ? null : found.dir();
    }

    /** 这个会话上下文现在大约多少 token：上一次模型回复实测的用量。会话没载入、还没调过模型时为空。 */
    private static java.util.OptionalLong tokensOf(tantalum.value.state.KernelState state, tantalum.value.id.SessionId session) {
        tantalum.value.state.SessionState found = state.sessions().get(session);
        return found == null || found.anchor() == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(found.anchor().tokens());
    }

    private static void processed(EventEnvelope envelope, Receipts receipts, Intake intake, LaneDispatcher lanes) {
        receipts.processed(envelope);
        intake.processed(envelope);
        lanes.released(envelope);
    }
}
