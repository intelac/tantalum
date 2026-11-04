package tantalum.examples;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.support.GenericApplicationContext;
import tantalum.shell.auth.UserStore;
import tantalum.shell.boot.TantalumApplication;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFile;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.mcp.TestMcpServer;
import tantalum.shell.model.ModelClient;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.OwnerRef;

/**
 * Playwright 端到端测试用的服务：固定端口、临时数据目录、脚本化模型（{@link E2eModel}），不访问外网。
 * 启动时创建用户 {@value #USER}（密码 {@value #PASSWORD}）、两个示例文件和一个运行被崩溃打断的会话 {@value #CRASHED_SESSION}。
 * 由 {@code console/playwright.config.ts} 通过 {@code mvn -P e2e} 启动，进程被杀即结束。
 */
public final class E2eServer {

    public static final String USER = "e2e";
    public static final String PASSWORD = "e2e-password";
    /** 第二个账号：插件开关每人各管各的。 */
    public static final String OTHER_USER = "e2e2";
    public static final int DEFAULT_PORT = 8097;
    /** 启动前写好的、运行被进程崩溃打断的会话。 */
    public static final String CRASHED_SESSION = "s-c0ffee00";

    private E2eServer() {}

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        Path root = Files.createTempDirectory("tantalum-e2e-");
        Path data = root.resolve("data");
        Path workspace = root.resolve("workspace");
        seed(data, workspace);
        TestMcpServer mcp = new TestMcpServer(
                new TestMcpServer.Definition("lookup", true, arguments -> TestMcpServer.text("ticket 42 is owned by e2e-owner")),
                new TestMcpServer.Definition("delete_all", null, arguments -> TestMcpServer.text("deleted")));

        new SpringApplicationBuilder(TantalumApplication.class)
                .initializers(context -> {
                    GenericApplicationContext generic = (GenericApplicationContext) context;
                    generic.registerBean(ModelClient.class, E2eModel::new);
                    generic.registerBean(E2eController.class);
                    // 轨迹页的端到端测试：示例插件的引用核对工作，加一个只对 trace: 提问起作用的钩子。
                    generic.registerBean(CitationWork.class);
                    generic.registerBean(E2eTraceHook.class);
                })
                .run("--server.port=" + port,
                        "--tantalum.data-dir=" + data,
                        "--tantalum.workspace=" + workspace,
                        "--tantalum.provider=openai",
                        "--tantalum.openai.model=" + E2eModel.MODEL,
                        "--tantalum.openai.base-url=http://127.0.0.1:9/v1",
                        "--tantalum.openai.context-window=32000",
                        "--tantalum.openai.vision=true",
                        "--tantalum.mcp.servers.demo.url=" + mcp.url(),
                        // 空闲 2 秒就回收：整套端到端测试都在不断回收、重新载入的情况下跑。
                        "--tantalum.residency.idle=2s");
        System.out.println("E2E server ready on http://127.0.0.1:" + port + " data=" + root);
        Thread.currentThread().join();
    }

    private static void seed(Path data, Path workspace) throws Exception {
        try (UserStore store = UserStore.open(data)) {
            store.add(USER, PASSWORD);
            store.add(OTHER_USER, PASSWORD);
        }
        Path notes = Files.createDirectories(workspace.resolve(USER).resolve("notes"));
        Files.writeString(notes.resolve("hello.md"), "# hello\n\nhello e2e\n");
        Files.writeString(notes.resolve("world.md"), "# world\n\nhello back from world\n");
        // 上次进程在这个运行刚开始时被杀：会话文件停在用户提问之后。启动时它要收尾为“因重启中断”。
        try (SessionFile file = SessionFile.open(new SessionPaths(data), new Principal(USER), new SessionId(CRASHED_SESSION), EventCodec.withBuiltins())) {
            file.append(List.of(new EventEnvelope(0, Instant.parse("2026-11-01T09:00:00Z"), new Principal(USER), new SessionId(CRASHED_SESSION), null,
                    new UserPrompt(new SubmissionId("crashed-submission"), new RequestId("crashed-request"),
                            new OwnerRef.OfSession(new SessionId(CRASHED_SESSION)), "hello 崩溃前"))));
        }
    }
}
