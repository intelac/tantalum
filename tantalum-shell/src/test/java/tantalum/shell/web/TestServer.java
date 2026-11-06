package tantalum.shell.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import tantalum.shell.auth.UserStore;
import tantalum.shell.boot.Engine;
import tantalum.shell.boot.TantalumApplication;
import tantalum.shell.local.TestComputer;
import tantalum.shell.model.ModelClient;
import tantalum.value.id.Principal;

/**
 * 启动真实的服务（随机端口），模型用替身，测试走真实 HTTP。
 * {@code principal} 参数是用户名：第一次用到时创建账号并真实登录，之后带上它的 Cookie；null 表示未登录。
 */
public final class TestServer implements AutoCloseable {

    public static final String PASSWORD = "test-password";

    public final ConfigurableApplicationContext context;
    public final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> cookies = new ConcurrentHashMap<>();

    public TestServer(Path dataDir, Path workspace, ModelClient model, String... extra) {
        this(dataDir, workspace, model, context -> { }, extra);
    }

    public TestServer(Path dataDir, Path workspace, ModelClient model, Consumer<GenericApplicationContext> beans, String... extra) {
        seedFirstUser(dataDir);
        String[] base = {
            "--server.port=0",
            "--tantalum.data-dir=" + dataDir,
            "--tantalum.workspace=" + workspace
        };
        String[] args = java.util.stream.Stream.concat(List.of(base).stream(), List.of(extra).stream()).toArray(String[]::new);
        context = new SpringApplicationBuilder(TantalumApplication.class)
                .initializers(ctx -> {
                    GenericApplicationContext generic = (GenericApplicationContext) ctx;
                    generic.registerBean(ModelClient.class, () -> model);
                    beans.accept(generic);
                })
                .run(args);
    }

    private static void seedFirstUser(Path dataDir) {
        try (UserStore store = UserStore.open(dataDir)) {
            if (store.count() == 0) {
                store.add("test-admin", PASSWORD);
            }
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    public int port() {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    public UserStore users() {
        return context.getBean(UserStore.class);
    }

    /**
     * 给 {@code user} 连一台测试电脑，允许 {@code dir}（local-workspace）：文件工具在“电脑”上执行，不再在服务器上。
     * 服务关闭时连接一起断开。
     */
    public TestComputer connectComputer(String user, Path dir, boolean write) throws java.io.IOException {
        return TestComputer.connect(context.getBean(Engine.class).localLinks(), new Principal(user),
                java.time.Instant.now().plus(java.time.Duration.ofDays(1)), dir, write, false);
    }

    public HttpResponse<String> get(String principal, String path) throws Exception {
        return http.send(request(principal, path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> post(String principal, String path, String body) throws Exception {
        return http.send(request(principal, path).header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpRequest.Builder request(String principal, String path) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + path));
        if (principal != null) {
            request.header("Cookie", cookies.computeIfAbsent(principal, this::login));
        }
        return request;
    }

    /** 不经过缓存，发一次真实的登录请求。 */
    public HttpResponse<String> attemptLogin(String username, String password, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + "/api/auth/login"))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login(String username) {
        try {
            if (users().status(username).isEmpty()) {
                users().add(username, PASSWORD);
            }
            HttpResponse<String> response = attemptLogin(username, PASSWORD, null);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("login failed: " + response.statusCode() + " " + response.body());
            }
            return response.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public void close() {
        context.close();
    }
}
