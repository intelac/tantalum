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
import tantalum.shell.loop.Cleanroom;
import tantalum.shell.loop.CleanroomRing;
import tantalum.shell.loop.DeadlineTimers;
import tantalum.shell.loop.Evictor;
import tantalum.shell.loop.ExternalGate;
import tantalum.shell.loop.HandlerDispatcher;
import tantalum.shell.loop.InFlightCommands;
import tantalum.shell.loop.InputRing;
import tantalum.shell.loop.Intake;
import tantalum.shell.loop.LaneDispatcher;
import tantalum.shell.loop.OutputRing;
import tantalum.shell.loop.Receipts;
import tantalum.shell.loop.Release;
import tantalum.shell.loop.Residency;
import tantalum.shell.loop.ResultPublisher;
import tantalum.shell.loop.SubmissionLedger;
import tantalum.shell.memory.KnowledgeBase;
import tantalum.shell.store.SessionQueries;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.DoWork;
import tantalum.value.command.RunTool;
import tantalum.value.command.StartTimer;
import tantalum.value.event.EventEnvelope;

/**
 * 装配第四步：管线——内核、输入环、用户通道、净室、输出环、处理器、入口。这里只建不起：
 * 环由 {@link Engine} 在装配完成后启动。
 */
record Pipeline(Residency residency, CleanroomRing cleanroomRing, LaneDispatcher lanes, InFlightCommands inFlight, InputRing input,
                DeadlineTimers deadlines, HandlerDispatcher handlers, OutputRing output, Cleanroom cleanroom, Intake intake) {

    /** 只供跳号回归：让序号跳号运行，证明没有代码依赖序号连续。 */
    private static final String SEQUENCE_STEP = "tantalum.test.sequence-step";

    static Pipeline open(TantalumProperties properties, Stores stores, Toolbox toolbox, Queries queries, Clock clock, Receipts receipts,
                         BooleanSupplier closing, List<KernelHook> hooks, List<AutoCloseable> opened) {
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
        Map<Class<? extends Command>, Handler> byCommand = Map.of(
                AssembleContext.class, new ContextHandler(toolbox.localWorkspace(), properties.systemPrompt(), toolbox.catalog(), toolbox.gate(),
                        stores.memories(), new KnowledgeBase(properties.knowledge().path(), properties.knowledge().maxBytes()),
                        toolbox.fixedNote(), toolbox.compaction(), toolbox.routes(), stores.userSettings()),
                CallModel.class, new ModelHandler(toolbox.routes(), queries.hub()::chunks, clock,
                        run -> new StoredMedia(storage.attachments, requestImages, run.principal(), run.session())),
                RunTool.class, new ToolHandler(toolbox.catalog(), stores.workspaces(), toolbox.gate(), properties.toolTimeout(), clock),
                DoWork.class, new WorkHandler(stores.workspaces(), toolbox.gate(), properties.toolTimeout(), clock),
                StartTimer.class, new TimerHandler());
        HandlerDispatcher handlers = new HandlerDispatcher(byCommand, inFlight, results);
        OutputRing output = new OutputRing(loop.outputRingSize(), handlers);
        Evictor evictor = new Evictor(properties.residency(), residency, queries.projections(), clock, storage.files::forget);
        // 处理完的一步由用户通道写成一行、落盘后才放行命令和推送，提交的回执也在这时完成；那之后才归还外部名额和放行名额
        Release saved = (envelope, record, afterDurable, done) -> lanes.release(envelope, record, () -> {
            afterDurable.run();
            receipts.durable(envelope);
        }, done);
        // 一个事件走完全程时归还外部名额和放行名额；入口在净室之后才建，所以经一个槽位取
        Intake[] intakeSlot = new Intake[1];
        Cleanroom cleanroom = new Cleanroom(kernel, lanes::halted, output::tryPublish, queries.projections(), queries.hub(), queries.view(), results,
                storage.indexWriter::onStep, evictor, saved, envelope -> processed(envelope, receipts, intakeSlot[0], lanes));
        // 文件写不进去、内核之外抛出、任一环的消费者出错，入口都返回 503，而不是让提交静默消失。
        BooleanSupplier halted = () -> closing.getAsBoolean() || lanes.halted() || cleanroom.broken() || input.broken()
                || cleanroomRing.broken() || output.broken();
        ExternalGate gate = new ExternalGate(new ExternalGate.Limits(loop.maxActiveRuns(), loop.maxUserInFlight(), loop.maxExternalInFlight()),
                () -> (int) queries.view().current().activeRunCount(), halted);
        SubmissionLedger ledger = new SubmissionLedger(storage::findSubmission, SubmissionLedger.DEFAULT_CAPACITY);
        Intake intake = new Intake(input, gate, ledger, queries.view(), residency, storage::claim,
                (principal, session) -> queries.queries().session(principal, session).map(SessionQueries.SessionView::state), clock, receipts);
        intakeSlot[0] = intake;
        input.connect(lanes);
        cleanroomRing.connect(cleanroom);
        lanes.onHalt(receipts::failAll);
        return new Pipeline(residency, cleanroomRing, lanes, inFlight, input, deadlines, handlers, output, cleanroom, intake);
    }

    private static void processed(EventEnvelope envelope, Receipts receipts, Intake intake, LaneDispatcher lanes) {
        receipts.processed(envelope);
        intake.processed(envelope);
        lanes.released(envelope);
    }
}
