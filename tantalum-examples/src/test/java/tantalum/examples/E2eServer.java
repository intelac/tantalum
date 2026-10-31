package tantalum.examples;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.support.GenericApplicationContext;
import tantalum.shell.auth.UserStore;
import tantalum.shell.boot.TantalumApplication;
import tantalum.shell.model.ModelClient;

/**
 * Playwright 端到端测试用的服务：固定端口、临时数据目录、脚本化模型（{@link E2eModel}），不访问外网。
 * 启动时创建用户 {@value #USER}（密码 {@value #PASSWORD}）和两个示例文件。
 * 由 {@code console/playwright.config.ts} 通过 {@code mvn -P e2e} 启动，进程被杀即结束。
 */
public final class E2eServer {

    public static final String USER = "e2e";
    public static final String PASSWORD = "e2e-password";
    public static final int DEFAULT_PORT = 8097;

    private E2eServer() {}

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        Path root = Files.createTempDirectory("tantalum-e2e-");
        Path data = root.resolve("data");
        Path workspace = root.resolve("workspace");
        seed(data, workspace);

        new SpringApplicationBuilder(TantalumApplication.class)
                .initializers(context -> {
                    GenericApplicationContext generic = (GenericApplicationContext) context;
                    generic.registerBean(ModelClient.class, E2eModel::new);
                    generic.registerBean(E2eController.class);
                })
                .run("--server.port=" + port,
                        "--tantalum.data-dir=" + data,
                        "--tantalum.workspace=" + workspace,
                        "--tantalum.provider=openai",
                        "--tantalum.openai.model=" + E2eModel.MODEL,
                        "--tantalum.openai.base-url=http://127.0.0.1:9/v1",
                        "--tantalum.openai.context-window=32000");
        System.out.println("E2E server ready on http://127.0.0.1:" + port + " data=" + root);
        Thread.currentThread().join();
    }

    private static void seed(Path data, Path workspace) throws Exception {
        try (UserStore store = UserStore.open(data)) {
            store.add(USER, PASSWORD);
        }
        Path notes = Files.createDirectories(workspace.resolve(USER).resolve("notes"));
        Files.writeString(notes.resolve("hello.md"), "# hello\n\nhello e2e\n");
        Files.writeString(notes.resolve("world.md"), "# world\n\nhello back from world\n");
    }
}
