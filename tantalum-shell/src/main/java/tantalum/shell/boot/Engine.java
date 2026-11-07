package tantalum.shell.boot;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.access.PluginGate;
import tantalum.shell.access.WebAccess;
import tantalum.shell.attachment.AttachmentStore;
import tantalum.shell.browser.Browsers;
import tantalum.shell.handler.Handler;
import tantalum.shell.local.LocalLinks;
import tantalum.shell.local.LocalWorkspace;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.loop.InFlightCommands;
import tantalum.shell.loop.Intake;
import tantalum.shell.loop.Receipts;
import tantalum.shell.mcp.McpRegistry;
import tantalum.shell.memory.MemoryStores;
import tantalum.shell.net.AddressRules;
import tantalum.shell.net.Egress;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.route.Routes;
import tantalum.shell.settings.CompactionPolicy;
import tantalum.shell.settings.UserSettings;
import tantalum.shell.sse.SseHub;
import tantalum.shell.store.SessionIndex;
import tantalum.shell.store.SessionQueries;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.RunInterrupted;
import tantalum.value.state.AwaitingAnswer;
import tantalum.value.state.KernelState;

/**
 * 把存储、环、Handler 和插件装配起来，负责启动和关闭。提交走 {@link #intake()}，读取走 {@link #queries()}。不依赖 Spring。
 * 装配分四步：{@link Stores}、{@link Toolbox}、{@link Queries}、{@link Pipeline}，这里只串起来；
 * 每一步打开的东西都记进 {@code opened}，启动到一半失败时倒序关掉。
 */
public final class Engine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Engine.class);
    private static final Duration DRAIN = Duration.ofSeconds(5);
    private static final Duration INTERRUPT_RETRY = Duration.ofMillis(10);
    private static final Duration INTERRUPT_GRACE = Duration.ofSeconds(2);

    private final Clock clock;
    private final EventCodec codec;
    private final Duration shutdownWait;
    private final Stores stores;
    private final Toolbox toolbox;
    private final Queries queries;
    private final Pipeline pipeline;
    /** 提交的回执：那一步落盘后完成，接口才回“已接受”。 */
    private final Receipts receipts = new Receipts();
    /** 关闭开始后入口返回 503。 */
    private final AtomicBoolean closing = new AtomicBoolean();

    /**
     * 打开存储，建环，把 Handler 接上，收尾上次没结束的运行，然后开门。失败时已经打开的倒序关掉再抛。
     * {@code tools}、{@code hooks}、{@code workTypes}、{@code afterHooks} 是插件注册的；{@code routes} 是模型路线，这里启动它。
     */
    public Engine(TantalumProperties properties, Routes routes,
                  List<Tool> tools, List<KernelHook> hooks, List<WorkType> workTypes, List<AfterToolHook> afterHooks,
                  EventCodec codec, Clock clock) throws IOException {
        long started = System.nanoTime();
        this.clock = clock;
        this.codec = codec;
        this.shutdownWait = properties.shutdownWait();
        List<AutoCloseable> opened = new ArrayList<>();
        opened.add(routes);
        Stores openedStores;
        Toolbox openedToolbox;
        Queries openedQueries;
        Pipeline openedPipeline;
        List<SessionIndex.Session> unfinished;
        try {
            openedStores = Stores.open(properties, codec, clock, opened);
            openedToolbox = Toolbox.open(properties, openedStores, routes, tools, hooks, workTypes, afterHooks, clock, opened);
            unfinished = openedStores.storage().catchUp();
            openedQueries = Queries.open(openedStores, codec);
            openedPipeline = Pipeline.open(properties, openedStores, openedToolbox, openedQueries, clock, receipts, closing::get, hooks, opened);
        } catch (RuntimeException | IOException failure) {
            closeAll(opened, failure);
            throw failure;
        }
        this.stores = openedStores;
        this.toolbox = openedToolbox;
        this.queries = openedQueries;
        this.pipeline = openedPipeline;
        int interrupted;
        try {
            pipeline.output().start();
            pipeline.cleanroomRing().start();
            pipeline.input().start();
            interrupted = interrupt(unfinished);
            try {
                pipeline.deadlines().arm(stores.storage().index.awaitingWithDeadline());
            } catch (SQLException failure) {
                throw new IllegalStateException("cannot read the answer deadlines left by the previous process", failure);
            }
        } catch (RuntimeException failure) {
            // 环已经起来了：按正常关机的顺序关，线程、文件锁、数据库都放掉
            try {
                close();
            } catch (IOException | RuntimeException more) {
                failure.addSuppressed(more);
            }
            throw failure;
        }
        Warmup.start(codec);
        LOG.info("store ready at {} in {} ms: {} runs left unfinished by the previous process closed as interrupted, {} waiting for an answer",
                properties.dataDir(), Duration.ofNanos(System.nanoTime() - started).toMillis(), interrupted, unfinished.size() - interrupted);
    }

    /** 启动到一半失败时：已经打开的倒序关掉，关不掉的挂在原来的异常上，原来的异常更要紧。 */
    private static void closeAll(List<AutoCloseable> opened, Throwable failure) {
        for (int i = opened.size() - 1; i >= 0; i--) {
            try {
                opened.get(i).close();
            } catch (Exception more) {
                failure.addSuppressed(more);
            }
        }
    }

    /** 提问、排队、插话、手动压缩和取消。 */
    public Intake intake() {
        return pipeline.intake();
    }

    /** 每个用户的附件目录。 */
    public AttachmentStore attachments() {
        return stores.storage().attachments;
    }

    /** 会话、运行和回放的读取。 */
    public SessionQueries queries() {
        return queries.queries();
    }

    public SseHub hub() {
        return queries.hub();
    }

    /** 插件工作问用户的、还没回答的问题。 */

    /** 插件开关和唯一的调用入口，插件页接口用它。 */
    public PluginGate plugins() {
        return toolbox.gate();
    }

    /** 每个用户选的压缩点按模型算出的压缩设置，设置接口和 {@code /api/models} 用它。 */
    public CompactionPolicy compaction() {
        return toolbox.compaction();
    }

    /** 全部模型路线和它们的模型列表。 */
    public Routes routes() {
        return toolbox.routes();
    }

    public UserSettings userSettings() {
        return stores.userSettings();
    }

    /** 连到电脑的连接：WebSocket 端点、转发工具和插件页接口用它。 */
    public LocalLinks localLinks() {
        return toolbox.localLinks();
    }

    /** 本机工作区的开关和可用性，插件页接口用它。 */
    public LocalWorkspace localWorkspace() {
        return toolbox.localWorkspace();
    }

    public McpRegistry mcp() {
        return toolbox.mcp();
    }

    /** 联网的开关组，插件页接口用它。 */
    public WebAccess webAccess() {
        return toolbox.webAccess();
    }

    /** 出口；管理员把抓网页和浏览器都关了时没有。给诊断和测试用。 */
    public Optional<Egress> egress() {
        return Optional.ofNullable(toolbox.egress());
    }

    /** 每个用户的浏览器；管理员关了浏览器时没有。给诊断和测试用。 */
    public Optional<Browsers> browsers() {
        return Optional.ofNullable(toolbox.browsers());
    }

    /** 联网的地址规则：Web 服务器起来后由 Spring 设置实际端口（{@code WebServerInitializedEvent}），访问自己永远被拦。 */
    public AddressRules addressRules() {
        return toolbox.addressRules();
    }

    /** 每用户的长期记忆，接口和工具共用同一份实例。 */
    public MemoryStores memories() {
        return stores.memories();
    }

    public EventCodec codec() {
        return codec;
    }

    /** 注册了的全部工具（不含 MCP 的），按名称排序，给诊断和测试用。 */
    public List<Tool> tools() {
        return toolbox.catalog().tools().stream().filter(tool -> toolbox.catalog().isRegistered(tool.name())).toList();
    }

    /** 内存里的内核状态，给诊断和测试用。 */
    public KernelState state() {
        return queries.view().current();
    }

    /** 某个 MCP 工具当前是否对模型可见。 */
    public boolean mcpHas(String tool) {
        return toolbox.mcp().find(tool).isPresent();
    }

    /**
     * 重启前没结束的运行在开门前收尾，等它们走完管线，这样第一个请求看到的就是结束后的状态。
     * 这些会话和平时一样由用户通道从文件载入；载入失败的会话按平时的规则拒绝，不拦住启动。
     */
    private int interrupt(List<SessionIndex.Session> all) {
        // 等用户回答的运行没有在途命令，不收尾，照旧等
        List<SessionIndex.Session> unfinished = all.stream().filter(session -> !AwaitingAnswer.NAME.equals(session.runStatus())).toList();
        if (unfinished.isEmpty()) {
            return 0;
        }
        try {
            long deadline = System.nanoTime() + DRAIN.toNanos();
            for (SessionIndex.Session session : unfinished) {
                EventEnvelope envelope = new EventEnvelope(EventEnvelope.UNASSIGNED, clock.instant(), session.principal(),
                        session.session(), session.activeRun(), new RunInterrupted());
                // 环满了等一会儿再试，但有上限；环坏了或等太久不能一直转下去
                while (!pipeline.input().tryPublish(envelope)) {
                    if (pipeline.input().broken()) {
                        throw new IllegalStateException("the input ring is broken; the runs left unfinished by the previous process cannot be closed");
                    }
                    if (System.nanoTime() - deadline > 0) {
                        throw new IllegalStateException("the input ring did not accept the interruptions of the unfinished runs within " + DRAIN);
                    }
                    Thread.sleep(INTERRUPT_RETRY.toMillis());
                }
            }
            if (!pipeline.input().awaitDrained(DRAIN)) {
                LOG.warn("the input ring did not drain the interruptions of {} unfinished runs within {}", unfinished.size(), DRAIN);
            }
            if (!pipeline.lanes().awaitIdle(DRAIN)) {
                LOG.warn("{} unfinished runs were not closed within {}", unfinished.size(), DRAIN);
            }
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while closing the runs left unfinished by the previous process", stopping);
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
                new Shutdown.Step("close event streams", queries.hub()::closeEveryone),
                new Shutdown.Step("stop starting handlers", pipeline.handlers()::close),
                new Shutdown.Step("wait for running handlers", this::awaitHandlers),
                new Shutdown.Step("stop answer deadline timers", pipeline.deadlines()),
                new Shutdown.Step("close local links", toolbox.localLinks()),
                new Shutdown.Step("close browsers", () -> {
                    if (toolbox.browsers() != null) {
                        toolbox.browsers().close();
                    }
                }),
                new Shutdown.Step("close egress", () -> {
                    if (toolbox.egress() != null) {
                        toolbox.egress().close();
                    }
                }),
                new Shutdown.Step("drain input ring", () -> pipeline.input().shutdown(DRAIN)),
                new Shutdown.Step("drain user lanes", () -> pipeline.lanes().awaitIdle(DRAIN)),
                // 之后不会再有落盘：还在等的回执全部以失败完成，接口不再等
                new Shutdown.Step("fail pending receipts", receipts::failAll),
                new Shutdown.Step("drain cleanroom ring", () -> pipeline.cleanroomRing().shutdown(DRAIN)),
                new Shutdown.Step("drain output ring", () -> pipeline.output().shutdown(DRAIN)),
                new Shutdown.Step("close mcp", toolbox.mcp()),
                new Shutdown.Step("close plugin settings", stores.switches()),
                new Shutdown.Step("close local tokens", stores.localTokens()),
                new Shutdown.Step("close user settings", stores.userSettings()),
                new Shutdown.Step("stop reading the model lists", toolbox.routes()),
                new Shutdown.Step("close storage", stores.storage())));
    }

    /** 到时中断；被中断的 Handler 的结果不写回，重启时按 H9 收尾。不响应中断的再等一小会儿就不管了。 */
    private void awaitHandlers() throws InterruptedException {
        InFlightCommands inFlight = pipeline.inFlight();
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
