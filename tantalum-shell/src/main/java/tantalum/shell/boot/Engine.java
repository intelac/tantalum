package tantalum.shell.boot;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.kernel.Kernel;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.access.PluginGate;
import tantalum.shell.access.PluginSwitches;
import tantalum.shell.access.WebAccess;
import tantalum.shell.attachment.AttachmentStore;
import tantalum.shell.attachment.RequestImages;
import tantalum.shell.attachment.StoredMedia;
import tantalum.shell.attachment.ToolImages;
import tantalum.shell.browser.BrowserDriver;
import tantalum.shell.browser.BrowserTools;
import tantalum.shell.browser.Browsers;
import tantalum.shell.boot.TantalumProperties.Loop;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.fetch.Fetcher;
import tantalum.shell.fetch.WebFetchTool;
import tantalum.shell.handler.ContextHandler;
import tantalum.shell.handler.Handler;
import tantalum.shell.handler.ModelHandler;
import tantalum.shell.handler.TimerHandler;
import tantalum.shell.handler.ToolHandler;
import tantalum.shell.handler.WorkHandler;
import tantalum.shell.local.LocalLinks;
import tantalum.shell.local.LocalTokens;
import tantalum.shell.local.LocalTool;
import tantalum.shell.local.LocalWorkspace;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.RecordCodec;
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
import tantalum.shell.loop.Projections;
import tantalum.shell.loop.Receipts;
import tantalum.shell.loop.Release;
import tantalum.shell.loop.Residency;
import tantalum.shell.loop.ResultPublisher;
import tantalum.shell.loop.StateView;
import tantalum.shell.loop.SubmissionLedger;
import tantalum.shell.mcp.McpRegistry;
import tantalum.shell.mcp.McpSettings;
import tantalum.shell.memory.KnowledgeBase;
import tantalum.shell.memory.MemoryStores;
import tantalum.shell.memory.MemoryTools;
import tantalum.shell.net.AddressRules;
import tantalum.shell.net.Egress;
import tantalum.shell.net.UpstreamProxy;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.route.Routes;
import tantalum.shell.settings.CompactionPolicy;
import tantalum.shell.settings.UserSettings;
import tantalum.shell.sse.SseHub;
import tantalum.shell.store.ColdReads;
import tantalum.shell.store.SessionIndex;
import tantalum.shell.store.SessionQueries;
import tantalum.shell.store.TraceQuery;
import tantalum.shell.store.TurnQuery;
import tantalum.shell.tool.BuiltinTools;
import tantalum.shell.tool.ToolCatalog;
import tantalum.shell.tool.Workspaces;
import tantalum.shell.trace.Trace;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.DoWork;
import tantalum.value.command.RunTool;
import tantalum.value.command.StartTimer;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.RunInterrupted;
import tantalum.value.state.AwaitingAnswer;
import tantalum.value.state.KernelState;

/** 把存储、环、Handler 和插件装配起来，负责启动和关闭。提交走 {@link #intake()}，读取走 {@link #queries()}。不依赖 Spring。 */
public final class Engine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Engine.class);
    private static final Duration DRAIN = Duration.ofSeconds(5);
    private static final Duration INTERRUPT_RETRY = Duration.ofMillis(10);
    private static final Duration INTERRUPT_GRACE = Duration.ofSeconds(2);
    /** 读模型列表：启动时几条路线并行，总共最多等 5 秒，之后每 10 分钟（model-picker M2，同 DSH {@code listing-config.ts:7,9}）。 */
    private static final Duration LIST_STARTUP_WAIT = Duration.ofSeconds(5);
    /** 只供跳号回归：让序号跳号运行，证明没有代码依赖序号连续。 */
    private static final String SEQUENCE_STEP = "tantalum.test.sequence-step";

    private final Clock clock;
    private final EventCodec codec;
    private final Duration shutdownWait;
    private final Storage storage;
    private final StateView view;
    private final SseHub hub;
    /** 插件工作在控制台上问用户的问题（如工具审批），页面经 {@code QuestionController} 取和回答。 */
    private final MemoryStores memories;
    private final McpRegistry mcp;
    private final ToolCatalog catalog;
    private final PluginSwitches switches;
    /** 连接电脑的令牌和连接（local-workspace）：转发工具经它在用户的电脑上执行。 */
    private final LocalTokens localTokens;
    private final LocalLinks localLinks;
    private final LocalWorkspace localWorkspace;
    /** 联网（web-tools）：地址规则总是有；出口和浏览器只在管理员开着对应的项时才有，否则为 null。 */
    private final AddressRules addressRules;
    private final Egress egress;
    private final Browsers browsers;
    private final WebAccess webAccess;
    private final UserSettings userSettings;
    private final Routes routes;
    private final CompactionPolicy compaction;
    private final PluginGate gate;
    private final InputRing input;
    private final DeadlineTimers deadlines;
    private final LaneDispatcher lanes;
    /** 提交的回执：那一步落盘后完成，接口才回“已接受”（one-line-journal J4）。 */
    private final Receipts receipts = new Receipts();
    private final CleanroomRing cleanroomRing;
    private final Cleanroom cleanroom;
    private final OutputRing output;
    private final HandlerDispatcher handlers;
    private final InFlightCommands inFlight;
    private final Intake intake;
    private final SessionQueries queries;
    /** 关闭开始后入口返回 503。 */
    private final AtomicBoolean closing = new AtomicBoolean();

    /** {@code routes} 还没开始读列表，在这里开始；关机时停掉刷新。 */
    public Engine(TantalumProperties properties, Routes routes,
                  List<Tool> tools, List<KernelHook> hooks, List<WorkType> workTypes, List<AfterToolHook> afterHooks,
                  EventCodec codec, Clock clock) throws IOException {
        long started = System.nanoTime();
        this.clock = clock;
        this.codec = codec;
        this.shutdownWait = properties.shutdownWait();
        this.storage = Storage.open(properties.dataDir(), codec, properties.index(), properties.attachmentLimits());
        Loop loop = properties.loop();
        List<SessionIndex.Session> unfinished;
        try {
            catalog = new ToolCatalog();
            Workspaces workspaces = KernelFactory.workspaces(properties);
            if (workspaces.isShared()) {
                LOG.warn("tantalum.workspace-mode=shared: all users share {} and can read each other's files.", properties.workspace());
            }
            localTokens = openLocalTokens(properties.dataDir(), clock);
            localLinks = new LocalLinks(localTokens, clock, LocalLinks.Timing.DEFAULT);
            // 本机工作区读到的图片、MCP 返回的图片、浏览器截图都照附件规则存进本人附件（web-tools W8）
            ToolImages toolImages = new ToolImages(storage.attachments);
            // 服务器上只读附件；读写文件、Git 都转发到用户的电脑（local-workspace L6）
            BuiltinTools.of(storage.attachments).forEach(catalog::register);
            LocalTool.all(localLinks, toolImages, properties.toolTimeout()).forEach(catalog::register);
            memories = new MemoryStores(workspaces, properties.dataDir(), properties.memory(), clock);
            MemoryTools.all(memories).forEach(catalog::register);
            tools.forEach(catalog::register);
            TantalumProperties.Web web = properties.webSettings();
            addressRules = new AddressRules(web.blockPrivateNetwork());
            egress = web.any() ? Egress.start(addressRules, UpstreamProxy.fromEnvironment(System::getenv)) : null;
            if (web.fetch().enabled()) {
                catalog.register(new WebFetchTool(new Fetcher(egress, addressRules)));
            }
            // 浏览器懒启动：这里只登记，用户第一次调浏览器工具时才起 Chromium
            TantalumProperties.Browser browser = web.browser();
            browsers = browser.enabled() ? new Browsers(new Browsers.Settings(browser.maxOpen(), browser.idleClose(), browser.sandbox()),
                    egress, toolImages, clock, new BrowserDriver(properties.dataDir())) : null;
            if (browsers != null) {
                BrowserTools.all(browsers).forEach(catalog::register);
            }
            McpSettings mcpSettings = McpSettings.from(properties.mcp(), properties.toolTimeout(), System::getenv);
            mcp = new McpRegistry(mcpSettings, toolImages);
            catalog.setSource(mcp);
            switches = openSwitches(properties.dataDir(), clock);
            localWorkspace = new LocalWorkspace(switches, localLinks);
            // 管理员关掉的那一项不注册工具；这一组照样进开关，插件页照样显示“联网”一块
            webAccess = new WebAccess(switches, web.fetch().enabled(), browser.enabled(), web.blockPrivateNetwork());
            userSettings = openUserSettings(properties.dataDir(), clock);
            // 每个模型的窗口在它的路线的列表里，每次提问按这次用的模型算压缩设置（model-picker M10）
            this.routes = routes.start(LIST_STARTUP_WAIT);
            compaction = new CompactionPolicy(userSettings, properties.compactionLimits());
            gate = new PluginGate(catalog, switches, List.of(localWorkspace, webAccess), mcpSettings.servers().stream().map(McpSettings.Server::name).toList(),
                    hooks, afterHooks, workTypes, Set.copyOf(properties.hooks().required()));

            RequestImages requestImages = new RequestImages(storage.attachments);
            Kernel kernel = KernelFactory.kernel(properties, catalog.safety(), hooks);
            unfinished = storage.catchUp();

            Residency residency = new Residency(clock, properties.residency().loadRetry());
            Transcript transcript = new Transcript();
            Projections projections = new Projections(transcript, new Trace());
            view = new StateView();
            TurnQuery turns = new TurnQuery(storage.paths, storage.records, codec.mapper());
            hub = new SseHub(1024, 4096, turns::messagesAfter, codec.mapper());
            queries = new SessionQueries(view, transcript, storage.index, new ColdReads(storage.paths,
                    storage.records::read),
                    turns, new TraceQuery(storage.paths, storage.records, new RecordCodec(codec)));

            cleanroomRing = new CleanroomRing(loop.inputRingSize());
            lanes = new LaneDispatcher(loop.inputRingSize(), loop.maxUserPermits(), new TailSequence(storage.paths, Integer.getInteger(SEQUENCE_STEP, 1)),
                    storage.files::append, cleanroomRing::tryPublish, storage.loader(), residency);
            inFlight = new InFlightCommands();
            input = new InputRing(loop.inputRingSize());
            deadlines = new DeadlineTimers(clock, input::tryPublish);
            ResultPublisher results = new ResultPublisher(input, inFlight, clock, loop.internalReserve());
            Map<Class<? extends Command>, Handler> byCommand = Map.of(
                    AssembleContext.class, new ContextHandler(localWorkspace, properties.systemPrompt(), catalog, gate, memories,
                            new KnowledgeBase(properties.knowledge().path(), properties.knowledge().maxBytes()),
                            fixedNote(mcp.fixedNote(), web.any()), compaction, routes, userSettings),
                    CallModel.class, new ModelHandler(routes, hub::chunks, clock,
                            run -> new StoredMedia(storage.attachments, requestImages, run.principal(), run.session())),
                    RunTool.class, new ToolHandler(catalog, workspaces, gate, properties.toolTimeout(), clock),
                    DoWork.class, new WorkHandler(workspaces, gate, properties.toolTimeout(), clock),
                    StartTimer.class, new TimerHandler());
            handlers = new HandlerDispatcher(byCommand, inFlight, results);
            output = new OutputRing(loop.outputRingSize(), handlers);
            Evictor evictor = new Evictor(properties.residency(), residency, projections, clock, storage.files::forget);
            // 处理完的一步由用户通道写成一行、落盘后才放行命令和推送，提交的回执也在这时完成；
            // 那之后才归还外部名额和放行名额（record-results R3、one-line-journal J2、J4）
            Release saved = (envelope, record, afterDurable, done) -> lanes.release(envelope, record, () -> {
                afterDurable.run();
                receipts.durable(envelope);
            }, done);
            cleanroom = new Cleanroom(kernel, lanes::halted, output::tryPublish, projections, hub, view, results,
                    storage.indexWriter::onStep, evictor, saved, this::processed);

            // 文件写不进去、内核之外抛出、任一环的消费者出错，入口都返回 503，而不是让提交静默消失。
            BooleanSupplier halted = () -> closing.get() || lanes.halted() || cleanroom.broken() || input.broken()
                    || cleanroomRing.broken() || output.broken();
            ExternalGate gate = new ExternalGate(new ExternalGate.Limits(loop.maxActiveRuns(), loop.maxUserInFlight(), loop.maxExternalInFlight()),
                    () -> (int) view.current().activeRunCount(), halted);
            SubmissionLedger ledger = new SubmissionLedger(storage::findSubmission, SubmissionLedger.DEFAULT_CAPACITY);
            intake = new Intake(input, gate, ledger, view, residency, storage::claim,
                    (principal, session) -> queries.session(principal, session).map(SessionQueries.SessionView::state), clock, receipts);
            input.connect(lanes);
            cleanroomRing.connect(cleanroom);
            lanes.onHalt(receipts::failAll);
        } catch (RuntimeException | IOException failure) {
            routes.close();
            closeWebQuietly();
            Storage.closeQuietly(storage);
            throw failure;
        }
        output.start();
        cleanroomRing.start();
        input.start();
        int interrupted = interrupt(unfinished);
        try {
            deadlines.arm(storage.index.awaitingWithDeadline());
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot read the answer deadlines left by the previous process", failure);
        }
        Warmup.start(codec);
        LOG.info("store ready at {} in {} ms: {} runs left unfinished by the previous process closed as interrupted, {} waiting for an answer",
                properties.dataDir(), Duration.ofNanos(System.nanoTime() - started).toMillis(), interrupted, unfinished.size() - interrupted);
    }

    /** 固定说明：MCP 的那段加联网的那段（W9），都不随用户开关变化。 */
    private static String fixedNote(String mcpNote, boolean web) {
        if (!web) {
            return mcpNote;
        }
        return mcpNote.isEmpty() ? WebAccess.NOTE : mcpNote + "\n\n" + WebAccess.NOTE;
    }

    /** 启动到一半失败时：已经起来的出口和浏览器线程不能留着。 */
    private void closeWebQuietly() {
        try {
            if (browsers != null) {
                browsers.close();
            }
            if (egress != null) {
                egress.close();
            }
        } catch (RuntimeException ignored) {
            // 启动本来就失败了，原来的异常更要紧
        }
    }

    /** 一个事件走完全程：归还外部名额和放行名额。 */
    private void processed(EventEnvelope envelope) {
        receipts.processed(envelope);
        intake.processed(envelope);
        lanes.released(envelope);
    }

    /** 提问、排队、插话、手动压缩和取消。 */
    public Intake intake() {
        return intake;
    }

    /** 每个用户的附件目录。 */
    public AttachmentStore attachments() {
        return storage.attachments;
    }

    /** 会话、运行和回放的读取。 */
    public SessionQueries queries() {
        return queries;
    }

    public SseHub hub() {
        return hub;
    }

    /** 插件工作问用户的、还没回答的问题。 */

    /** 插件开关和唯一的调用入口，插件页接口用它。 */
    public PluginGate plugins() {
        return gate;
    }

    /** 每个用户选的压缩点按模型算出的压缩设置，设置接口和 {@code /api/models} 用它（compaction-threshold、model-picker M10）。 */
    public CompactionPolicy compaction() {
        return compaction;
    }

    /** 全部模型路线和它们的模型列表（model-picker）。 */
    public Routes routes() {
        return routes;
    }

    public UserSettings userSettings() {
        return userSettings;
    }

    /** 连到电脑的连接：WebSocket 端点、转发工具和插件页接口用它。 */
    public LocalLinks localLinks() {
        return localLinks;
    }

    /** 本机工作区的开关和可用性，插件页接口用它。 */
    public LocalWorkspace localWorkspace() {
        return localWorkspace;
    }

    public McpRegistry mcp() {
        return mcp;
    }

    /** 联网的开关组，插件页接口用它。 */
    public WebAccess webAccess() {
        return webAccess;
    }

    /** 出口；管理员把抓网页和浏览器都关了时没有。给诊断和测试用。 */
    public Optional<Egress> egress() {
        return Optional.ofNullable(egress);
    }

    /** 每个用户的浏览器；管理员关了浏览器时没有。给诊断和测试用。 */
    public Optional<Browsers> browsers() {
        return Optional.ofNullable(browsers);
    }

    /** 联网的地址规则：Web 服务器起来后由 Spring 设置实际端口（{@code WebServerInitializedEvent}），访问自己永远被拦（W6）。 */
    public AddressRules addressRules() {
        return addressRules;
    }

    /** 每用户的长期记忆，接口和工具共用同一份实例。 */
    public MemoryStores memories() {
        return memories;
    }

    public EventCodec codec() {
        return codec;
    }

    /** 注册了的全部工具（不含 MCP 的），按名称排序，给诊断和测试用。 */
    public List<Tool> tools() {
        return catalog.tools().stream().filter(tool -> catalog.isRegistered(tool.name())).toList();
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
    private int interrupt(List<SessionIndex.Session> all) {
        // 等用户回答的运行没有在途命令，不收尾，照旧等（waiting-state 需求 3.1）
        List<SessionIndex.Session> unfinished = all.stream().filter(session -> !AwaitingAnswer.NAME.equals(session.runStatus())).toList();
        if (unfinished.isEmpty()) {
            return 0;
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
        return unfinished.size();
    }

    /**
     * 关闭：入口返回 503 → 断开 SSE → 不再启动新的 Handler，等在途的最多 {@code shutdown-wait}，到时中断 →
     * 关浏览器，再关出口（浏览器的连接都经出口，需求 2.8）→ 排空输入环、各用户通道和两个下游环 → 关 MCP 和存储。
     * 每一步失败都不跳过后面的。
     * 已返回 200 的提交在排空输入环时落盘；没走完的运行留在文件里，下次启动时以“因重启中断”收尾。
     */
    @Override
    public void close() throws IOException {
        Shutdown.run(List.of(
                new Shutdown.Step("refuse new submissions", () -> closing.set(true)),
                new Shutdown.Step("close event streams", hub::closeEveryone),
                new Shutdown.Step("stop starting handlers", handlers::close),
                new Shutdown.Step("wait for running handlers", this::awaitHandlers),
                new Shutdown.Step("stop answer deadline timers", deadlines),
                new Shutdown.Step("close local links", localLinks),
                new Shutdown.Step("close browsers", () -> {
                    if (browsers != null) {
                        browsers.close();
                    }
                }),
                new Shutdown.Step("close egress", () -> {
                    if (egress != null) {
                        egress.close();
                    }
                }),
                new Shutdown.Step("drain input ring", () -> input.shutdown(DRAIN)),
                new Shutdown.Step("drain user lanes", () -> lanes.awaitIdle(DRAIN)),
                // 之后不会再有落盘：还在等的回执全部以失败完成，接口不再等（review-fixes 需求 1.3）
                new Shutdown.Step("fail pending receipts", receipts::failAll),
                new Shutdown.Step("drain cleanroom ring", () -> cleanroomRing.shutdown(DRAIN)),
                new Shutdown.Step("drain output ring", () -> output.shutdown(DRAIN)),
                new Shutdown.Step("close mcp", mcp),
                new Shutdown.Step("close plugin settings", switches),
                new Shutdown.Step("close local tokens", localTokens),
                new Shutdown.Step("close user settings", userSettings),
                new Shutdown.Step("stop reading the model lists", routes),
                new Shutdown.Step("close storage", storage)));
    }

    private static UserSettings openUserSettings(java.nio.file.Path dataDir, Clock clock) throws IOException {
        try {
            return UserSettings.open(dataDir, clock);
        } catch (java.sql.SQLException failure) {
            throw new IOException("cannot open user settings in " + dataDir, failure);
        }
    }

    private static LocalTokens openLocalTokens(java.nio.file.Path dataDir, Clock clock) throws IOException {
        try {
            return LocalTokens.open(dataDir, clock);
        } catch (java.sql.SQLException failure) {
            throw new IOException("cannot open local tokens in " + dataDir, failure);
        }
    }

    private static PluginSwitches openSwitches(java.nio.file.Path dataDir, Clock clock) throws IOException {
        try {
            return PluginSwitches.open(dataDir, clock);
        } catch (java.sql.SQLException failure) {
            throw new IOException("cannot open plugin settings in " + dataDir, failure);
        }
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
