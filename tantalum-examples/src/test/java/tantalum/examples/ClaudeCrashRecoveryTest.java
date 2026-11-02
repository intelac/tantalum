package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.store.SessionStore;
import tantalum.shell.model.ClaudeClient;
import tantalum.shell.model.OverflowRules;
import tantalum.shell.web.TestServer;
import tantalum.value.event.EventEnvelope;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 任务恢复，用真实的 Claude：在模型调用进行中杀掉 JVM，重启后运行自动收尾且不会重放那次调用；
 * 在同一个会话里继续提问，Anthropic 要接受这段带着一条没有回复的用户消息的历史。
 * 会产生费用；同时设置 {@code ANTHROPIC_API_KEY} 和 {@code TANTALUM_SMOKE_CLAUDE} 才运行。
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TANTALUM_SMOKE_CLAUDE", matches = ".+")
class ClaudeCrashRecoveryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void killedDuringAClaudeCallThenRecoveredAndContinued() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Files.createDirectory(workspace.resolve("alice"));
        Files.writeString(workspace.resolve("alice/note.txt"), "The secret word is HERON-5120.\n");
        Path data = temp.resolve("data");

        int port = freePort();
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                "tantalum.shell.boot.TantalumApplication",
                "--server.port=" + port,
                "--tantalum.data-dir=" + data,
                "--tantalum.workspace=" + workspace,
                "--tantalum.provider=claude",
                "--tantalum.claude.effort=high")
                .redirectErrorStream(true).redirectOutput(temp.resolve("child.log").toFile());
        builder.environment().put("TANTALUM_ADMIN_USER", "alice");
        builder.environment().put("TANTALUM_ADMIN_PASSWORD", TestServer.PASSWORD);
        Process child = builder.start();
        String runId;
        try {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;
            HttpRequest login = HttpRequest.newBuilder(URI.create(base + "/api/auth/login")).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"alice\",\"password\":\"" + TestServer.PASSWORD + "\"}")).build();
            HttpResponse<String> loggedIn = null;
            for (int i = 0; i < 600 && loggedIn == null; i++) {
                try {
                    loggedIn = http.send(login, HttpResponse.BodyHandlers.ofString());
                } catch (java.net.ConnectException notYet) {
                    Thread.sleep(100);
                }
            }
            String cookie = loggedIn.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
            HttpRequest submit = HttpRequest.newBuilder(URI.create(base + "/api/sessions/s1/submissions"))
                    .header("Cookie", cookie).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":"
                            + "\"Before using any tool, reason carefully about the following: a snail climbs 3 m by day and slips 2 m by night "
                            + "up a 25 m wall; on which day does it reach the top, and why do people often get this wrong? "
                            + "Then read note.txt with read_file and report the secret word.\"}")).build();
            assertEquals(200, http.send(submit, HttpResponse.BodyHandlers.ofString()).statusCode(), () -> "child log at " + temp.resolve("child.log"));

            // 等到运行进入“等待模型”，立刻杀掉。Claude 的一次调用要几秒，轮询 10 毫秒足够抓住。
            HttpRequest runs = HttpRequest.newBuilder(URI.create(base + "/api/sessions/s1/runs")).header("Cookie", cookie).GET().build();
            runId = null;
            Instant deadline = Instant.now().plusSeconds(120);
            while (runId == null && Instant.now().isBefore(deadline)) {
                JsonNode body = JSON.readTree(http.send(runs, HttpResponse.BodyHandlers.ofString()).body());
                JsonNode list = body.get("runs");
                if (list != null && !list.isEmpty() && list.get(0).get("phase").asString().equals("AwaitingModel")) {
                    runId = list.get(0).get("run").asString();
                } else {
                    Thread.sleep(10);
                }
            }
            assertTrue(runId != null, "never observed AwaitingModel; see " + temp.resolve("child.log"));
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
        } finally {
            child.destroyForcibly();
        }

        List<EventEnvelope> events;
        events = new SessionStore(data, EventCodec.withBuiltins()).readAll();
        List<String> types = events.stream().map(envelope -> envelope.event().type()).toList();
        assertTrue(types.getLast().equals("ContextAssembled") || types.getLast().equals("ToolFinished"),
                "the journal must end right before a model call: " + types);

        ClaudeClient client = new ClaudeClient(AnthropicOkHttpClient.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).maxRetries(0).build(),
                "claude-sonnet-5-5", 8192, "low", true, Duration.ofSeconds(300), Duration.ofSeconds(300), OverflowRules.defaults());
        try (TestServer restarted = new TestServer(data, workspace, client)) {
            JsonNode view = JSON.readTree(restarted.get("alice", "/api/runs/" + runId).body());
            assertEquals("CANCELLED", view.get("endReason").asString(), view.toString());
            assertEquals("interrupted by restart", view.get("detail").asString(), view.toString());

            assertEquals(200, restarted.post("alice", "/api/sessions/s1/submissions",
                    "{\"requestId\":\"r2\",\"mode\":\"prompt\",\"text\":\"Just read note.txt with read_file and tell me the secret word.\"}").statusCode());
            JsonNode latest = null;
            Instant deadline = Instant.now().plusSeconds(240);
            while (Instant.now().isBefore(deadline)) {
                JsonNode list = JSON.readTree(restarted.get("alice", "/api/sessions/s1/runs").body()).get("runs");
                latest = list.get(list.size() - 1);
                if (!latest.get("run").asString().equals(runId) && latest.has("endReason")) {
                    break;
                }
                Thread.sleep(100);
            }
            assertEquals("COMPLETED", latest.get("endReason").asString(), "the conversation must continue after recovery: " + latest);
            String newRun = latest.get("run").asString();
            JsonNode turns = JSON.readTree(restarted.get("alice", "/api/runs/" + newRun + "/turns").body()).get("turns");
            String last = restarted.get("alice", "/api/runs/" + newRun + "/turns/" + turns.get(turns.size() - 1).get("number").asInt()).body();
            assertTrue(last.contains("HERON-5120"), last);
            System.out.println("[claude recovery] journalBeforeCrash=" + types + " recoveredRun=" + runId + " newRun=" + newRun);
        }
    }
}
