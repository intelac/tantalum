package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.auth.UserStore;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.mcp.TestMcpServer;
import tantalum.shell.store.SessionStore;
import tantalum.shell.web.TestServer;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.model.TokenUsage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 需求 9.3：打包的 jar 被 {@code kill -9}，用同一个 jar 重启。
 * 被杀时 alice 有一个已结束的会话和一个正在等模型的会话，bob 有一个正在等 MCP 工具的会话；
 * 杀掉之后再把索引里已结束的那个会话改回落后的样子（偏移、提交记录、用量都缺一截）。
 * 重启后：会话文件只多不少，索引补齐，两个运行收尾为“因重启中断”，模型和工具都没有再被调用；
 * 两个会话都能继续，发给模型的历史里每个工具调用都有结果。
 * 只在 {@code mvn verify} 的 integration-test 阶段跑，那时 jar 已经打好。
 */
class JarCrashIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    // 失败时留下两个进程的日志。
    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    Path temp;

    /** 按最后一条用户消息里的标记回复，记录每种标记被调用的次数。 */
    private static final class FakeModel implements AutoCloseable {
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        final List<String> unpaired = new CopyOnWriteArrayList<>();
        final CountDownLatch modelHung = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final HttpServer server;

        FakeModel() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", this::handle);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        int total() {
            return calls.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        Map<String, Integer> counts() {
            return calls.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get()));
        }

        private void handle(HttpExchange exchange) throws IOException {
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            JsonNode messages = body.get("messages");
            checkPairs(messages);
            String last = "";
            boolean toolResult = false;
            for (JsonNode message : messages) {
                String role = message.path("role").asString();
                if (role.equals("user")) {
                    last = text(message.get("content"));
                    toolResult = false;
                } else if (role.equals("tool")) {
                    toolResult = true;
                }
            }
            String marker = last.substring(last.indexOf('['), last.indexOf(']') + 1);
            calls.computeIfAbsent(marker, key -> new AtomicInteger()).incrementAndGet();
            switch (marker) {
                case "[done]" -> send(exchange, toolResult ? text("ticket 42 belongs to e2e-owner") : call("c-done", "mcp__demo__lookup"));
                case "[hang-tool]" -> send(exchange, toolResult ? text("slow finished") : call("c-slow", "mcp__demo__slow"));
                case "[hang-model]" -> {
                    modelHung.countDown();
                    try {
                        release.await(120, TimeUnit.SECONDS);
                    } catch (InterruptedException stopping) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                }
                default -> send(exchange, text("continued after the crash"));
            }
        }

        /** 每个助手消息里的工具调用都要在后面有对应的工具结果，否则真实服务会返回 400。 */
        private void checkPairs(JsonNode messages) {
            Set<String> answered = new HashSet<>();
            for (JsonNode message : messages) {
                if (message.path("role").asString().equals("tool")) {
                    answered.add(message.path("tool_call_id").asString());
                }
            }
            for (JsonNode message : messages) {
                for (JsonNode call : message.path("tool_calls")) {
                    if (!answered.contains(call.path("id").asString())) {
                        unpaired.add(call.path("id").asString());
                    }
                }
            }
        }

        private static String text(JsonNode content) {
            if (content == null || content.isNull()) {
                return "";
            }
            if (content.isString()) {
                return content.asString();
            }
            StringBuilder text = new StringBuilder();
            content.forEach(part -> text.append(part.path("text").asString()));
            return text.toString();
        }

        private static List<ObjectNode> text(String answer) {
            ObjectNode delta = JSON.createObjectNode();
            delta.put("content", answer);
            return chunks(delta, "stop");
        }

        private static List<ObjectNode> call(String id, String name) {
            ObjectNode delta = JSON.createObjectNode();
            ObjectNode call = delta.putArray("tool_calls").addObject();
            call.put("index", 0);
            call.put("id", id);
            call.putObject("function").put("name", name).put("arguments", "{}");
            return chunks(delta, "tool_calls");
        }

        private static List<ObjectNode> chunks(ObjectNode delta, String finish) {
            ObjectNode first = JSON.createObjectNode();
            first.putArray("choices").addObject().set("delta", delta);
            ObjectNode end = JSON.createObjectNode();
            ObjectNode choice = end.putArray("choices").addObject();
            choice.putObject("delta");
            choice.put("finish_reason", finish);
            ObjectNode usage = JSON.createObjectNode();
            usage.putArray("choices");
            usage.putObject("usage").put("prompt_tokens", 120).put("completion_tokens", 15);
            return List.of(first, end, usage);
        }

        private static void send(HttpExchange exchange, List<ObjectNode> chunks) throws IOException {
            StringBuilder stream = new StringBuilder();
            chunks.forEach(chunk -> stream.append("data: ").append(chunk).append("\n\n"));
            stream.append("data: [DONE]\n\n");
            byte[] bytes = stream.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            release.countDown();
            server.stop(0);
        }
    }

    /** 一个登录用户。 */
    private record User(HttpClient http, String base, String cookie) {
        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Cookie", cookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        JsonNode json(String path) throws Exception {
            HttpResponse<String> response = get(path);
            assertEquals(200, response.statusCode(), path + " " + response.body());
            return JSON.readTree(response.body());
        }

        JsonNode submit(String session, String request, String text) throws Exception {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/sessions/" + session + "/submissions"))
                    .header("Cookie", cookie).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"requestId\":\"" + request + "\",\"mode\":\"prompt\",\"text\":\"" + text + "\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return JSON.readTree(response.body());
        }

        /** 会话里最新一个运行的视图。 */
        JsonNode latestRun(String session) throws Exception {
            JsonNode runs = json("/api/sessions/" + session + "/runs").get("runs");
            return runs.isEmpty() ? null : runs.get(runs.size() - 1);
        }

        JsonNode awaitEnded(String session, int runs) throws Exception {
            Instant deadline = Instant.now().plusSeconds(60);
            while (Instant.now().isBefore(deadline)) {
                // 提交是异步的：第一个事件落盘之前会话还不存在。
                HttpResponse<String> response = get("/api/sessions/" + session + "/runs");
                JsonNode list = response.statusCode() == 404 ? JSON.createArrayNode() : JSON.readTree(response.body()).get("runs");
                if (list.size() >= runs && list.get(runs - 1).has("endReason")) {
                    return list.get(runs - 1);
                }
                Thread.sleep(50);
            }
            throw new AssertionError("run " + runs + " of " + session + " did not end: " + get("/api/sessions/" + session + "/runs").body());
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private Process start(String name, int port, Path data, Path workspace, FakeModel model, TestMcpServer mcp) throws IOException {
        Path jar = Path.of(System.getProperty("tantalum.jar"));
        assertTrue(Files.isRegularFile(jar), "packaged jar missing: " + jar);
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString(),
                "--server.port=" + port,
                "--tantalum.data-dir=" + data,
                "--tantalum.workspace=" + workspace,
                "--tantalum.provider=openai",
                "--tantalum.openai.base-url=" + model.baseUrl(),
                // 默认并发 1 是给本地模型的；这里 alice 挂住的模型调用不能挡住 bob。
                "--tantalum.openai.concurrency=4",
                "--tantalum.mcp.servers.demo.url=" + mcp.url())
                .redirectErrorStream(true).redirectOutput(temp.resolve(name + ".log").toFile());
        builder.environment().remove("ANTHROPIC_API_KEY");
        return builder.start();
    }

    private static User login(String base, String name) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest login = HttpRequest.newBuilder(URI.create(base + "/api/auth/login")).header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + name + "\",\"password\":\"" + TestServer.PASSWORD + "\"}")).build();
        for (int i = 0; i < 600; i++) {
            try {
                HttpResponse<String> response = http.send(login, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                return new User(http, base, response.headers().firstValue("set-cookie").orElseThrow().split(";")[0]);
            } catch (java.net.ConnectException notYet) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("server never came up at " + base);
    }

    /** 每个会话的事件，按（序号, 类型）。 */
    private static Map<String, List<String>> journal(Path data) {
        Map<String, List<String>> sessions = new LinkedHashMap<>();
        for (EventEnvelope envelope : new SessionStore(data, EventCodec.withBuiltins()).readAll()) {
            sessions.computeIfAbsent(envelope.session().value(), key -> new ArrayList<>())
                    .add(envelope.seq() + " " + envelope.event().type());
        }
        return sessions;
    }

    private static List<Long> seqs(Path data, String session) {
        return new SessionStore(data, EventCodec.withBuiltins()).readAll().stream()
                .filter(envelope -> envelope.session().value().equals(session)).map(EventEnvelope::seq).toList();
    }

    /** 会话文件里每个带用量的模型回复，排列方式和 {@link #usageRows} 相同：索引的用量表应当正好是这些。 */
    private static String journalUsage(Path data) {
        return new SessionStore(data, EventCodec.withBuiltins()).readAll().stream()
                .filter(envelope -> envelope.event() instanceof ModelReplied replied && replied.usage() != null)
                .sorted(Comparator.comparing((EventEnvelope envelope) -> envelope.principal().value()).thenComparingLong(EventEnvelope::seq))
                .map(envelope -> {
                    TokenUsage usage = ((ModelReplied) envelope.event()).usage();
                    return envelope.principal().value() + ":" + envelope.seq() + ":" + usage.input() + ":" + usage.output() + " ";
                })
                .collect(Collectors.joining());
    }

    private static long count(Connection index, String sql, String... params) throws SQLException {
        try (PreparedStatement statement = index.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setString(i + 1, params[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    private static String usageRows(Connection index) throws SQLException {
        StringBuilder rows = new StringBuilder();
        try (PreparedStatement statement = index.prepareStatement("SELECT principal, seq, input, output FROM usage ORDER BY principal, seq");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.append(result.getString(1)).append(':').append(result.getLong(2)).append(':')
                        .append(result.getLong(3)).append(':').append(result.getLong(4)).append(' ');
            }
        }
        return rows.toString();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void killedJarRestartsWithEverythingCommittedAndNothingRepeated() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Files.createDirectory(workspace.resolve("alice"));
        Files.createDirectory(workspace.resolve("bob"));
        Path data = temp.resolve("data");
        try (UserStore users = UserStore.open(data)) {
            users.add("alice", TestServer.PASSWORD);
            users.add("bob", TestServer.PASSWORD);
        }
        CountDownLatch toolHung = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (FakeModel model = new FakeModel();
             TestMcpServer mcp = new TestMcpServer(
                     new TestMcpServer.Definition("lookup", true, arguments -> TestMcpServer.text("ticket 42 is owned by e2e-owner")),
                     new TestMcpServer.Definition("slow", true, arguments -> {
                         toolHung.countDown();
                         try {
                             release.await(120, TimeUnit.SECONDS);
                         } catch (InterruptedException stopping) {
                             Thread.currentThread().interrupt();
                         }
                         return TestMcpServer.text("slow finished");
                     }))) {
            int port = freePort();
            String base = "http://127.0.0.1:" + port;
            Process first = start("first", port, data, workspace, model, mcp);
            String doneSubmission;
            String doneMessages;
            JsonNode doneSummary;
            String modelRun;
            String toolRun;
            try {
                User alice = login(base, "alice");
                User bob = login(base, "bob");
                doneSubmission = alice.submit("s-done", "r1", "[done] who owns ticket 42?").get("submissionId").asString();
                assertEquals("COMPLETED", alice.awaitEnded("s-done", 1).get("endReason").asString());
                alice.submit("s-model", "r2", "[hang-model] think hard");
                bob.submit("s-tool", "r3", "[hang-tool] run the slow tool");
                assertTrue(model.modelHung.await(30, TimeUnit.SECONDS), "alice's model call never started");
                assertTrue(toolHung.await(30, TimeUnit.SECONDS), "bob's tool call never started");
                modelRun = alice.latestRun("s-model").get("run").asString();
                toolRun = bob.latestRun("s-tool").get("run").asString();
                assertEquals("AwaitingModel", alice.latestRun("s-model").get("phase").asString());
                assertEquals("AwaitingTools", bob.latestRun("s-tool").get("phase").asString());
                doneMessages = alice.get("/api/sessions/s-done/messages").body();
                doneSummary = alice.json("/api/sessions").get("sessions").valueStream()
                        .filter(session -> session.get("id").asString().equals("s-done")).findFirst().orElseThrow();
            } finally {
                first.destroyForcibly();
            }
            assertTrue(first.waitFor(10, TimeUnit.SECONDS));
            assertEquals(137, first.exitValue(), "SIGKILL ends the JVM with 128 + 9");

            Map<String, List<String>> beforeRestart = journal(data);
            Map<String, Integer> callsAtCrash = model.counts();
            List<String> toolsAtCrash = List.copyOf(mcp.calls);
            assertEquals(Map.of("[done]", 2, "[hang-model]", 1, "[hang-tool]", 1), callsAtCrash);
            assertEquals(List.of("lookup", "slow"), toolsAtCrash);
            assertTrue(beforeRestart.get("s-model").getLast().endsWith("ContextAssembled"), beforeRestart.toString());
            assertTrue(beforeRestart.get("s-tool").getLast().endsWith("ModelReplied"), beforeRestart.toString());

            // 让索引落后：已结束的会话回到“什么都没写过”，它的提交记录和用量也删掉。
            try (Connection index = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("index.db"))) {
                assertEquals(1, count(index, "SELECT count(*) FROM submissions WHERE principal = ? AND request_id = 'r1'", "alice"));
                try (PreparedStatement rewind = index.prepareStatement(
                        "UPDATE sessions SET file_offset = 0, active_run = NULL, run_status = 'RUNNING', title = '' WHERE session = 's-done'")) {
                    assertEquals(1, rewind.executeUpdate());
                }
                try (PreparedStatement forget = index.prepareStatement("DELETE FROM submissions WHERE request_id = 'r1'")) {
                    assertEquals(1, forget.executeUpdate());
                }
                String doneSeqs = seqs(data, "s-done").stream().map(String::valueOf).collect(Collectors.joining(","));
                try (PreparedStatement forget = index.prepareStatement("DELETE FROM usage WHERE seq IN (" + doneSeqs + ")")) {
                    assertEquals(2, forget.executeUpdate(), "two model replies in s-done carried usage");
                }
            }

            Process second = start("second", port, data, workspace, model, mcp);
            try {
                User alice = login(base, "alice");
                User bob = login(base, "bob");
                JsonNode interruptedModel = alice.json("/api/runs/" + modelRun);
                assertEquals("CANCELLED", interruptedModel.path("endReason").asString(), interruptedModel.toString());
                assertEquals("interrupted by restart", interruptedModel.get("detail").asString());
                JsonNode interruptedTool = bob.json("/api/runs/" + toolRun);
                assertEquals("CANCELLED", interruptedTool.path("endReason").asString(), interruptedTool.toString());
                assertEquals("interrupted by restart", interruptedTool.get("detail").asString());

                // 已提交的事件一个不少：每个会话文件都以被杀前的内容开头。
                Map<String, List<String>> afterRestart = journal(data);
                beforeRestart.forEach((session, events) ->
                        assertEquals(events, afterRestart.get(session).subList(0, events.size()), session));
                assertEquals(beforeRestart.get("s-done"), afterRestart.get("s-done"));

                // 索引补齐：会话列表、提交去重、用量都回到被杀前的样子。
                assertEquals(doneMessages, alice.get("/api/sessions/s-done/messages").body());
                JsonNode summary = alice.json("/api/sessions").get("sessions").valueStream()
                        .filter(session -> session.get("id").asString().equals("s-done")).findFirst().orElseThrow();
                assertEquals(doneSummary, summary);
                JsonNode again = alice.submit("s-done", "r1", "[done] who owns ticket 42?");
                assertTrue(again.get("duplicate").asBoolean(), again.toString());
                assertEquals(doneSubmission, again.get("submissionId").asString());
                try (Connection index = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("index.db"))) {
                    assertEquals(journalUsage(data), usageRows(index));
                }
                String startup = Files.readString(temp.resolve("second.log"));
                assertTrue(startup.contains("index caught up with"), "startup log should report the catch-up");
                assertTrue(startup.contains("2 runs left unfinished by the previous process closed as interrupted"), "startup log should count the recovery");

                // 重启没有重新调用模型和工具。
                Thread.sleep(1000);
                assertEquals(callsAtCrash, model.counts());
                assertEquals(toolsAtCrash, List.copyOf(mcp.calls));

                // 两个会话都能继续，发给模型的历史里每个工具调用都有结果。
                alice.submit("s-model", "r4", "[after] go on");
                bob.submit("s-tool", "r5", "[after] go on");
                assertEquals("COMPLETED", alice.awaitEnded("s-model", 2).get("endReason").asString());
                assertEquals("COMPLETED", bob.awaitEnded("s-tool", 2).get("endReason").asString());
                assertEquals(callsAtCrash.values().stream().mapToInt(Integer::intValue).sum() + 2, model.total());
                assertEquals(2, model.calls.get("[after]").get());
                assertEquals(toolsAtCrash, List.copyOf(mcp.calls));
                assertTrue(model.unpaired.isEmpty(), "tool calls without results: " + model.unpaired);
            } finally {
                second.destroy();
                assertTrue(second.waitFor(60, TimeUnit.SECONDS), "graceful stop took too long");
                release.countDown();
            }
            assertFalse(Files.readString(temp.resolve("second.log")).contains("Exception"), "see " + temp.resolve("second.log"));
        }
    }
}
