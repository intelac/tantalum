package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.auth.UserStore;
import tantalum.shell.web.TestServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A1：部署不改可执行 jar 也能装上示例插件。打包好的可执行 jar 里没有示例插件；用 jar 里自带的 {@code PropertiesLauncher} 启动、
 * {@code loader.path} 指向示例 jar、加 {@code --tantalum.examples.enabled=true}，插件页就列出它们，策略插件默认关。
 * 这正是 README 写给部署的启动命令。
 */
class ExamplesJarIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void thePackagedJarLoadsTheExamplesJarFromTheLoaderPath() throws Exception {
        Path jar = Path.of(System.getProperty("tantalum.jar"));
        Path examples = Path.of(System.getProperty("tantalum.examples.jar"));
        assertTrue(Files.isRegularFile(examples), "examples jar missing: " + examples);
        try (ZipFile exec = new ZipFile(jar.toFile())) {
            assertTrue(exec.stream().noneMatch(entry -> entry.getName().contains("tantalum/examples/")), "the exec jar has no examples");
        }
        Path data = temp.resolve("data");
        try (UserStore users = UserStore.open(data)) {
            users.add("alice", TestServer.PASSWORD);
        }
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-Dloader.path=" + examples,
                "-cp", jar.toString(), "org.springframework.boot.loader.launch.PropertiesLauncher",
                "--server.port=" + port, "--tantalum.data-dir=" + data, "--tantalum.workspace=" + temp.resolve("ws"),
                "--tantalum.provider=local", "--tantalum.openai-routes.local.model=qwen3.8-flash-next-unsloth-ud-q4_k_xl", "--tantalum.openai-routes.local.context-window=131072",
                "--tantalum.openai-routes.local.base-url=http://127.0.0.1:9/v1",
                "--tantalum.examples.enabled=true"));
        Process server = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(temp.resolve("server.log").toFile()).start();
        try {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;
            String cookie = login(http, base);
            HttpResponse<String> plugins = http.send(HttpRequest.newBuilder(URI.create(base + "/api/plugins")).header("Cookie", cookie)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, plugins.statusCode(), plugins.body());
            List<String> listed = new ArrayList<>();
            for (JsonNode plugin : JSON.readTree(plugins.body()).get("plugins")) {
                listed.add(plugin.get("name").asString() + "=" + plugin.get("enabled").asBoolean());
            }
            assertEquals(List.of("citationCheck=true", "dailyQuota=false", "pinnedContext=true", "readOnly=false", "toolApproval=false",
                    "truncateOutput=true"), listed);
            assertTrue(Files.readString(temp.resolve("server.log")).contains("example plugins from tantalum-examples"));
        } finally {
            server.destroy();
            server.waitFor(30, TimeUnit.SECONDS);
        }
    }

    private static String login(HttpClient http, String base) throws Exception {
        HttpRequest login = HttpRequest.newBuilder(URI.create(base + "/api/auth/login")).header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"alice\",\"password\":\"" + TestServer.PASSWORD + "\"}")).build();
        for (int i = 0; i < 600; i++) {
            try {
                HttpResponse<String> response = http.send(login, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                return response.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
            } catch (ConnectException notYet) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("server never came up at " + base);
    }
}
