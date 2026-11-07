package tantalum.examples;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import tantalum.shell.auth.UserStore;
import tantalum.shell.boot.Engine;
import tantalum.shell.boot.TantalumApplication;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.ResultWriter;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.local.LocalLinks;
import tantalum.shell.local.TestComputer;
import tantalum.shell.mcp.TestMcpServer;
import tantalum.shell.model.ModelClient;
import tantalum.shell.route.RouteClients;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.StopReason;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.OwnerRef;

/**
 * Playwright 端到端测试用的服务：固定端口、临时数据目录、脚本化模型（{@link E2eModel}），不访问外网。
 * 启动时创建用户 {@value #USER}（密码 {@value #PASSWORD}）、两个示例文件和一个运行被崩溃打断的会话 {@value #CRASHED_SESSION}。
 * 文件工具在用户自己的电脑上执行（local-workspace）：{@value #USER} 和 {@value #OTHER_USER} 各连着一台测试电脑（{@link TestComputer}），
 * 允许各自的目录、可以改文件；{@value #LOCAL_USER} 什么都没连，本机工作区的端到端测试用它起真的连接程序。
 * 联网（web-tools）：自带一个只听 127.0.0.1 的测试页 {@value #WEB_PAGE_PATH}，脚本化模型的 {@code fetch:} 用 web_fetch 抓它；
 * 另起一个管理员关掉浏览器的服务（端口 = 主端口 + 1，{@code tantalum.web.browser.enabled=false}，只有账号 {@value #USER}），
 * 插件页的端到端测试在那里看“管理员已关闭”。它用 jar 里打包的控制台（{@code console/dist}），要先 {@code pnpm build}。
 * 由 {@code console/playwright.config.ts} 通过 {@code mvn -P e2e} 启动，进程被杀即结束。
 */
public final class E2eServer {

    public static final String USER = "e2e";
    public static final String PASSWORD = "e2e-password";
    /** 第二个账号：插件开关每人各管各的。 */
    public static final String OTHER_USER = "e2e2";
    /** 第三个账号：本机工作区的端到端测试用它连真的连接程序，不影响前两个账号的测试电脑。 */
    public static final String LOCAL_USER = "e2e3";
    /** 第四个账号：语言的端到端测试改它存的语言（console-i18n），不影响别的用例。 */
    public static final String I18N_USER = "e2e4";
    public static final int DEFAULT_PORT = 8097;
    /** 启动前写好的、运行被进程崩溃打断的会话。 */
    public static final String CRASHED_SESSION = "s-c0ffee00";
    /** 同上，进程死在工具执行途中：文件停在模型要调工具之后，没有结果行（record-results 需求 11.4）。 */
    public static final String CRASHED_TOOL_SESSION = "s-c0ffee01";

    /** 本地测试页：标题、正文里的标记，外加不该出现在 web_fetch 结果里的脚本。 */
    static final String WEB_PAGE_PATH = "/article";
    static final String WEB_PAGE = "<!doctype html><html><head><meta charset=\"utf-8\"><title>E2E 测试文章</title>"
            + "<script>document.title = 'script-ran-e2e';</script></head>"
            + "<body><h1>E2E 测试文章</h1><p>这是联网端到端测试的正文 web-e2e-body。</p></body></html>";

    private E2eServer() {}

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        Path root = Files.createTempDirectory("tantalum-e2e-");
        Path data = root.resolve("data");
        Path workspace = root.resolve("workspace");
        seed(data, workspace);
        E2eModel.webPage = "http://127.0.0.1:" + webSite().getAddress().getPort() + WEB_PAGE_PATH;
        adminOffServer(port + 1, root.resolve("admin-off"));
        TestMcpServer mcp = new TestMcpServer(
                new TestMcpServer.Definition("lookup", true, arguments -> TestMcpServer.text("ticket 42 is owned by e2e-owner")),
                new TestMcpServer.Definition("delete_all", null, arguments -> TestMcpServer.text("deleted")));

        ConfigurableApplicationContext app = new SpringApplicationBuilder(TantalumApplication.class)
                .initializers(context -> {
                    GenericApplicationContext generic = (GenericApplicationContext) context;
                    // 两条脚本化路线（model-picker 任务 16）：本地（默认）和 Claude 协议的一条，都用同一个剧本模型
                    E2eModel model = new E2eModel();
                    generic.registerBean(RouteClients.class, () -> new RouteClients(java.util.Map.of("local", model, "claude", model)));
                    generic.registerBean(E2eController.class);
                    // 轨迹页的端到端测试：示例插件的引用核对工作，加一个只对 trace: 提问起作用的钩子。
                    generic.registerBean(CitationWork.class);
                    generic.registerBean(E2eTraceHook.class);
                    generic.registerBean(E2eScopeHook.class);
                    // 工具审批的端到端测试（A4）：默认关，用例在插件页打开；审批写文件，在对话页上问
                    generic.registerBean(ToolApprovalHook.class, () -> new ToolApprovalHook(java.util.Set.of("write_file")));
                })
                .run("--server.port=" + port,
                        "--tantalum.data-dir=" + data,
                        "--tantalum.workspace=" + workspace,
                        "--tantalum.provider=local",
                        "--tantalum.openai-routes.local.model=" + E2eModel.MODEL,
                        "--tantalum.openai-routes.local.base-url=http://127.0.0.1:9/v1",
                        // 测试页在 127.0.0.1 上：内网默认拦，这里要放开
                        "--tantalum.web.block-private-network=false",
                        // 32000 加上联网 12 个工具定义的约 3000 token（web-tools），压缩发生的轮次和摘要调用的余量都和以前一样
                        "--tantalum.openai-routes.local.context-window=35000",
                        "--tantalum.openai-routes.local.vision=true",
                        // 选择器：本地路线两个模型，剧本模型有“关”“开”两档，scripted-plain 没有档位；Claude 路线里
                        // claude-sonnet-5 不支持会话中途工具变化（灰掉的用例）
                        "--tantalum.openai-routes.local.thinking=chat-template",
                        "--tantalum.openai-routes.local.list-models=false",
                        "--tantalum.openai-routes.local.models=" + E2eModel.MODEL + "," + E2eModel.PLAIN,
                        "--tantalum.openai-routes.local.overrides[" + E2eModel.PLAIN + "].thinking=none",
                        "--tantalum.claude.models=claude-sonnet-5-5,claude-sonnet-5",
                        "--tantalum.claude.context-window=1000000",
                        "--tantalum.mcp.servers.demo.url=" + mcp.url(),
                        // 空闲 2 秒就回收：整套端到端测试都在不断回收、重新载入的情况下跑。
                        "--tantalum.residency.idle=2s");
        LocalLinks links = app.getBean(Engine.class).localLinks();
        for (String user : List.of(USER, OTHER_USER)) {
            TestComputer.connect(links, new Principal(user), Instant.now().plus(Duration.ofDays(30)),
                    Files.createDirectories(workspace.resolve(user)), true, false);
        }
        System.out.println("E2E server ready on http://127.0.0.1:" + port + " data=" + root);
        Thread.currentThread().join();
    }

    /** 联网的测试页，只听 127.0.0.1 的随机端口（不是服务器自己的端口，所以不被拦）。 */
    private static com.sun.net.httpserver.HttpServer webSite() throws java.io.IOException {
        com.sun.net.httpserver.HttpServer site = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext(WEB_PAGE_PATH, exchange -> {
            byte[] page = WEB_PAGE.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
        });
        site.start();
        return site;
    }

    /** 管理员在配置里关掉了浏览器的服务（web-tools 需求 5.3）：只给插件页看，不连电脑、不接 MCP。 */
    private static void adminOffServer(int port, Path root) throws Exception {
        Path data = root.resolve("data");
        try (UserStore store = UserStore.open(data)) {
            store.add(USER, PASSWORD, true);
        }
        new SpringApplicationBuilder(TantalumApplication.class)
                .initializers(context -> ((GenericApplicationContext) context).registerBean(ModelClient.class, E2eModel::new))
                .run("--server.port=" + port,
                        "--tantalum.data-dir=" + data,
                        "--tantalum.workspace=" + root.resolve("workspace"),
                        "--tantalum.provider=local",
                        "--tantalum.openai-routes.local.model=" + E2eModel.MODEL,
                        "--tantalum.openai-routes.local.base-url=http://127.0.0.1:9/v1",
                        "--tantalum.openai-routes.local.context-window=35000",
                        "--tantalum.web.block-private-network=false",
                        "--tantalum.web.browser.enabled=false");
    }

    /** 第一个账号 {@value #USER} 是管理员（ops-hardening-2），users.spec.ts 用它管用户。 */
    private static void seed(Path data, Path workspace) throws Exception {
        try (UserStore store = UserStore.open(data)) {
            store.add(USER, PASSWORD, true);
            store.add(OTHER_USER, PASSWORD);
            store.add(LOCAL_USER, PASSWORD);
            store.add(I18N_USER, PASSWORD);
        }
        Path notes = Files.createDirectories(workspace.resolve(USER).resolve("notes"));
        Files.writeString(notes.resolve("hello.md"), "# hello\n\nhello e2e\n");
        Files.writeString(notes.resolve("world.md"), "# world\n\nhello back from world\n");
        // 上次进程在这个运行刚开始时被杀：会话文件停在用户提问之后。启动时它要收尾为“因重启中断”。
        ResultWriter.write(new SessionPaths(data), List.of(new EventEnvelope(0, Instant.parse("2026-11-01T09:00:00Z"), new Principal(USER),
                new SessionId(CRASHED_SESSION), null, new UserPrompt(new SubmissionId("crashed-submission"), new RequestId("crashed-request"),
                        new OwnerRef.OfSession(new SessionId(CRASHED_SESSION)), "hello 崩溃前"))));
        // 上次进程在执行工具时被杀：模型要读一个文件，命令已经发出，结果没有回来。
        Principal user = new Principal(USER);
        SessionId tool = new SessionId(CRASHED_TOOL_SESSION);
        RunId run = RunId.of(tool, 1);
        Instant at = Instant.parse("2026-11-01T09:05:00Z");
        ToolSpec readFile = new ToolSpec("read_file", "Read a file.", new Json.Obj(java.util.Map.of("type", new Json.Str("object"))), false);
        ResultWriter.write(new SessionPaths(data), List.of(
                new EventEnvelope(1, at, user, tool, null, new UserPrompt(new SubmissionId("crashed-tool-submission"),
                        new RequestId("crashed-tool-request"), new OwnerRef.OfSession(tool), "读一下 notes/hello.md")),
                new EventEnvelope(2, at, user, tool, run, new ContextAssembled(new CommandId(CRASHED_TOOL_SESSION + "_1-0"), "system",
                        List.of(readFile), null, List.of())),
                new EventEnvelope(3, at, user, tool, run, new ModelReplied(new CommandId(CRASHED_TOOL_SESSION + "_2-0"),
                        List.of(new ToolCallBlock("crashed-call", "read_file", "{\"path\":\"notes/hello.md\"}",
                                new Json.Obj(java.util.Map.of("path", new Json.Str("notes/hello.md"))))),
                        StopReason.TOOL_USE, E2eModel.MODEL, null, null, null, null))));
    }
}
