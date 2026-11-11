package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.net.LogCapture;
import tantalum.shell.plugin.Configurable;
import tantalum.shell.plugin.Param;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;

/**
 * plugin-settings 任务 25（需求 6：密码只给声明它的插件，别处都没有）：填一个随机密码，让工具真的用一次，
 * 然后在接口返回、数据目录的每个文件、推送、发给模型的内容和日志里找这串字，一处都不能有。
 */
class SecretLeakTest {

    /** 工具拿到的密码，证明它真的用上了（否则“没泄漏”可能只是因为没人读过）。 */
    private static final AtomicReference<String> USED = new AtomicReference<>();

    static final class Search implements Tool, Configurable {
        @Override public String name() { return "search"; }
        @Override public String description() { return "Search."; }
        @Override public Json schema() { return new Json.Obj(Map.of("type", new Json.Str("object"))); }
        @Override public List<Param> params() { return List.of(Param.number("maxChars", 500), Param.secret("apiKey")); }
        @Override public ToolOutput run(ToolContext context, Json arguments) {
            USED.set(context.secrets().get("apiKey").orElse(null));
            return ToolOutput.ok("three results");
        }
    }

    @TempDir
    Path temp;

    private final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());
    private final List<String> answers = Collections.synchronizedList(new ArrayList<>());

    private String send(TestServer server, String user, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = server.request(user, path).header("content-type", "application/json");
        HttpResponse<String> response = server.http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        answers.add(response.body());
        return response.body();
    }

    @Test
    void aSecretIsUsedByItsToolAndAppearsNowhereElse() throws Exception {
        String secret = "sec-" + UUID.randomUUID();
        USED.set(null);
        ModelClient model = (prompt, chunks) -> {
            prompts.add(prompt);
            boolean sawResult = prompt.messages().stream().flatMap(message -> message.content().stream())
                    .anyMatch(ToolResultBlock.class::isInstance);
            List<ContentBlock> reply = sawResult ? List.of(new TextBlock("done"))
                    : List.of(new ToolCallBlock("call-1", "search", "{}", new Json.Obj(Map.of())));
            return new ModelOutcome.Replied(reply, sawResult ? StopReason.END_TURN : StopReason.TOOL_USE, "m", null);
        };
        Path data = temp.resolve("data");
        List<String> pushed = new CopyOnWriteArrayList<>();
        String logs;
        try (LogCapture log = new LogCapture();
                TestServer server = new TestServer(data, temp.resolve("ws"), model,
                        context -> context.registerBean("search", Tool.class, Search::new))) {
            server.users().add("root", TestServer.PASSWORD, true);
            HttpResponse<InputStream> events = server.http.send(server.request("alice", "/api/events").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, events.statusCode());
            Thread.ofVirtual().start(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(events.body(), StandardCharsets.UTF_8))) {
                    in.lines().forEach(pushed::add);
                } catch (Exception closed) {
                    // 服务关了。
                }
            });

            // 填错位置的、不合格的请求也不能把它带出来
            send(server, "alice", "PUT", "/api/plugins/tool/search/params", "{\"apiKey\":\"" + secret + "\"}");
            send(server, "alice", "PUT", "/api/plugins/tool/search/params", "{\"values\":{\"" + secret + "\":1}}");
            send(server, "alice", "PUT", "/api/plugins/tool/search/params", "{\"values\":{\"apiKey\":\"" + secret + "\",\"maxChars\":\"big\"}}");
            String saved = send(server, "alice", "PUT", "/api/plugins/tool/search/params", "{\"values\":{\"apiKey\":\"" + secret + "\"}}");
            assertTrue(saved.contains("\"set\":true"), saved);

            assertEquals(200, server.post("alice", "/api/sessions/s1/submissions",
                    "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"search it\"}").statusCode());
            Instant deadline = Instant.now().plusSeconds(20);
            while (!server.get("alice", "/api/sessions/s1/runs").body().contains("COMPLETED")) {
                assertTrue(Instant.now().isBefore(deadline), "the run did not finish");
                Thread.sleep(5);
            }
            assertEquals(secret, USED.get(), "the tool got the secret");

            for (String path : List.of("/api/plugins", "/api/sessions/s1/messages", "/api/sessions/s1/trace", "/api/sessions/s1/runs",
                    "/api/sessions")) {
                send(server, "alice", "GET", path, null);
            }
            for (String path : List.of("/api/admin/plugins", "/api/admin/users/alice/params", "/api/users")) {
                send(server, "root", "GET", path, null);
            }
            assertTrue(pushed.stream().anyMatch(line -> line.contains("s1")), "the stream carried the run");
            logs = log.all();
        }

        assertFalse(String.join("\n", answers).contains(secret), "an api answer");
        assertFalse(String.join("\n", pushed).contains(secret), "the event stream");
        assertFalse(prompts.toString().contains(secret), "what the model was sent");
        assertFalse(logs.contains(secret), "the log");
        // 数据目录的每个文件：会话记录、索引、用户库（里面是密文）
        List<Path> files;
        try (Stream<Path> walk = Files.walk(data)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        assertTrue(files.stream().anyMatch(file -> file.toString().endsWith(".jsonl")), "the session was written: " + files);
        for (Path file : files) {
            assertFalse(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(secret), file.toString());
        }
    }
}
