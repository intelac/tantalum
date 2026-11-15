package tantalum.examples;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import tantalum.local.browser.PageText;
import tantalum.local.tool.LocalImage;
import tantalum.local.tool.LocalResult;
import tantalum.local.wire.Hello;
import tantalum.local.wire.Wire;
import tantalum.shell.auth.UserStore;
import tantalum.shell.boot.Engine;
import tantalum.shell.boot.TantalumApplication;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.ResultWriter;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.local.LocalLinks;
import tantalum.shell.local.TestComputer;
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
 * 另起一个管理员关掉抓网页的服务（端口 = 主端口 + 1，{@code tantalum.web.fetch.enabled=false}，只有账号 {@value #USER}），
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
    /**
     * 第五个账号：技能和知识库的端到端测试（skills-wiki）。它连着一台测试电脑，电脑上有 {@code skills/}（两个好的技能、一个没写描述的）
     * 和 {@code wiki/}；目录由用例在插件页上填，填了以后这个账号的 system 里多出两段、多一个 skill 工具，所以不和别的用例共用账号。
     */
    public static final String SKILL_USER = "e2e5";
    /**
     * 第六个账号：本机浏览器的端到端测试（local-browser）。它连着的测试电脑说“浏览器可用：Google Chrome，有窗口”，电脑上是一个假浏览器
     * （打开网页回一份固定的快照，截图回一张小图）；别的账号的电脑都没允许浏览器。
     */
    public static final String BROWSER_USER = "e2e6";
    /**
     * 第七个账号：跨平台连接程序的端到端测试（connector-jar）。它的测试电脑说协议 6、Windows、PowerShell 可用，报的版本 0.0.9 和服务器提供的 0.1.0 不一样（插件页提示换新）。
     */
    public static final String CONNECTOR_USER = "e2e7";
    public static final int DEFAULT_PORT = 8097;
    /** 启动前写好的、运行被进程崩溃打断的会话。 */
    public static final String CRASHED_SESSION = "s-c0ffee00";
    /** 同上，进程死在工具执行途中：文件停在模型要调工具之后，没有结果行（record-results 需求 11.4）。 */
    public static final String CRASHED_TOOL_SESSION = "s-c0ffee01";

    /** 第二个账号电脑上的长文件（400 字符），截断插件的端到端测试读它。名字里不能有 long：脚本模型见到这个词走别的剧本。 */
    static final String LONG_FILE = "big.md";

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

        ConfigurableApplicationContext app = new SpringApplicationBuilder(TantalumApplication.class)
                .initializers(context -> {
                    GenericApplicationContext generic = (GenericApplicationContext) context;
                    // 两条脚本化路线（model-picker 任务 16）：本地（默认）和 Claude 协议的一条，都用同一个剧本模型
                    E2eModel model = new E2eModel();
                    generic.registerBean(RouteClients.class, () -> new RouteClients(java.util.Map.of("local", model, "claude", model)));
                    generic.registerBean(E2eController.class);
                    // 轨迹页的端到端测试：示例插件的引用核对工作，加一个只对 trace: 提问起作用的钩子。
                    generic.registerBean(CitationWork.class);
                    // 示例面板“本轮概览”（plugin-panel 的端到端测试）
                    generic.registerBean(RunOverviewPanel.class);
                    generic.registerBean(E2eTraceHook.class);
                    generic.registerBean(E2eScopeHook.class);
                    // 工具审批的端到端测试（A4）：默认关，用例在插件页打开；审批写文件，在对话页上问
                    generic.registerBean(ToolApprovalHook.class, () -> new ToolApprovalHook(java.util.Set.of("write_file")));
                    // 插件参数的端到端测试（plugin-settings）：带一个密码参数的测试工具
                    generic.registerBean(E2eSecretTool.class);
                    // 同上：一个用户自己能改参数的插件（截断，默认上限大到碰不到别的用例），一个参数只归管理员的插件（每日额度，默认关）
                    generic.registerBean(TruncateOutputHook.class, () -> new TruncateOutputHook(100_000));
                    generic.registerBean(DailyQuotaHook.class, () -> new DailyQuotaHook(100_000_000L));
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
                        // 首屏那句话（input-area 需求 6）：默认一句，英文和日文各一句
                        "--tantalum.console.welcome=这里是端到端测试用的助手，随便问。",
                        "--tantalum.console.welcome-by-language.en=This is the end-to-end test assistant. Ask anything.",
                        "--tantalum.console.welcome-by-language.ja=エンドツーエンドテスト用のアシスタントです。",
                        // 空闲 2 秒就回收：整套端到端测试都在不断回收、重新载入的情况下跑。
                        "--tantalum.residency.idle=2s",
                        // connector-jar：插件页第 1 步能下载的连接程序（一个只有清单的小 jar，版本 0.1.0）
                        "--tantalum.local.connector-jar=" + connectorJar(root));
        LocalLinks links = app.getBean(Engine.class).localLinks();
        for (String user : List.of(USER, OTHER_USER, SKILL_USER)) {
            TestComputer.connect(links, new Principal(user), Instant.now().plus(Duration.ofDays(30)),
                    Files.createDirectories(workspace.resolve(user)), true, false);
        }
        TestComputer.connectWithBrowser(links, new Principal(BROWSER_USER), Instant.now().plus(Duration.ofDays(30)),
                Files.createDirectories(workspace.resolve(BROWSER_USER)), Hello.Browser.available("Google Chrome", false),
                E2eServer::fakeBrowser);
        TestComputer.connectAs(links, new Principal(CONNECTOR_USER), Instant.now().plus(Duration.ofDays(30)),
                Files.createDirectories(workspace.resolve(CONNECTOR_USER)), roots -> new Hello(Wire.PROTOCOL, "0.0.9",
                        new Hello.Device("E2E PC", "windows-11", "10.0", "amd64"), roots, true,
                        new Hello.Shell(true, null, null, null, "powershell", "C:\\Program Files\\PowerShell\\7\\pwsh.exe"), true, true,
                        Hello.Browser.unavailable(Hello.Browser.NODE_MISSING), "0".repeat(64)));
        System.out.println("E2E server ready on http://127.0.0.1:" + port + " data=" + root);
        Thread.currentThread().join();
    }

    /** 第六个账号电脑上的假浏览器：截图回一张 64×48 的 PNG，别的调用回同一份快照（标题 Board，一个标题元素）。 */
    private static LocalResult fakeBrowser(String session, String tool, Json arguments) throws IOException {
        if (tool.equals("browser_take_screenshot")) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "png", png);
            return LocalResult.image(PageText.screenshot("https://board.example/"),
                    new LocalImage("image/png", png.toByteArray(), "screenshot-board.example.png"));
        }
        return LocalResult.ok(PageText.snapshot("Board", "https://board.example/", 1, 1, "- heading \"3 open tasks\" [ref=e2]"));
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

    /** 管理员在配置里关掉了浏览器的服务（web-tools 需求 5.3）：只给插件页看，不连电脑。 */
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
                        // qagent-align 需求 6.9：这台服务器上部署的人关掉了“只读查看别人的会话”
                        "--tantalum.view-as.enabled=false",
                        "--tantalum.web.block-private-network=false",
                        "--tantalum.web.fetch.enabled=false");
    }

    /** 第一个账号 {@value #USER} 是管理员（ops-hardening-2），users.spec.ts 用它管用户。 */
    /** 一个只有清单的小 jar，当作服务器提供下载的连接程序。 */
    private static Path connectorJar(Path root) throws IOException {
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION, "0.1.0");
        Path file = root.resolve("tantalum-connect.jar");
        try (java.io.OutputStream out = Files.newOutputStream(file); java.util.jar.JarOutputStream jar = new java.util.jar.JarOutputStream(out, manifest)) {
            jar.flush();
        }
        return file;
    }

    private static void seed(Path data, Path workspace) throws Exception {
        try (UserStore store = UserStore.open(data)) {
            store.add(USER, PASSWORD, true);
            store.add(OTHER_USER, PASSWORD);
            store.add(LOCAL_USER, PASSWORD);
            store.add(I18N_USER, PASSWORD);
            store.add(SKILL_USER, PASSWORD);
            store.add(BROWSER_USER, PASSWORD);
            store.add(CONNECTOR_USER, PASSWORD);
        }
        // skills-wiki：第五个账号的电脑上放好技能和 wiki。名字避开脚本模型认的词（tools、slow、fail 之类）
        Path skills = Files.createDirectories(workspace.resolve(SKILL_USER).resolve("skills"));
        Files.writeString(Files.createDirectories(skills.resolve("greeting")).resolve("SKILL.md"),
                "---\nname: greeting\ndescription: Greet people the house way.\n---\nAlways open with greeting-e2e-body.\n");
        Files.writeString(Files.createDirectories(skills.resolve("by-hand")).resolve("SKILL.md"),
                "---\nname: by-hand\ndescription: Only when the user asks for it.\ndisable-model-invocation: true\n---\nManual steps e2e.\n");
        Files.writeString(Files.createDirectories(skills.resolve("unfinished")).resolve("SKILL.md"), "---\nname: unfinished\n---\nNo description yet.\n");
        Path wiki = Files.createDirectories(workspace.resolve(SKILL_USER).resolve("wiki").resolve("ops"));
        Files.writeString(wiki.resolve("rollback.md"), "# Rolling back\n\nUse the blue button.\n");
        Files.writeString(wiki.getParent().resolve("index.md"), "Start with ops/rollback.md (wiki-e2e-index).\n");
        Path notes = Files.createDirectories(workspace.resolve(USER).resolve("notes"));
        Files.writeString(notes.resolve("hello.md"), "# hello\n\nhello e2e\n");
        Files.writeString(notes.resolve("world.md"), "# world\n\nhello back from world\n");
        // plugin-settings：第二个账号的电脑上一个 400 字符的文件，截断的用例读它
        Files.writeString(Files.createDirectories(workspace.resolve(OTHER_USER)).resolve(LONG_FILE), "big-e2e-head " + "x".repeat(387));
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
