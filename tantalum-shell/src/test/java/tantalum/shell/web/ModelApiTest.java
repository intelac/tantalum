package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.boot.Engine;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.route.RouteClients;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * model-picker 任务 12（需求 1.6、4.1–4.4、5.6、5.7）：模型列表接口、提交里的选择、“上次的选择”。两条替身路线：Claude（允许
 * Sonnet 5.5、Sonnet 5、Opus 5.5）和本地 qwen（chat-template，档位 关/开，不收图片）。
 */
class ModelApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private TestServer server;
    private final List<Prompt> claudeCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<Prompt> localCalls = Collections.synchronizedList(new ArrayList<>());

    private static ModelClient answering(List<Prompt> calls) {
        return (prompt, chunks) -> {
            calls.add(prompt);
            return new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);
        };
    }

    @BeforeEach
    void start() {
        Map<String, ModelClient> clients = Map.of("claude", answering(claudeCalls), "local", answering(localCalls));
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), answering(claudeCalls),
                context -> context.registerBean(RouteClients.class, () -> new RouteClients(clients)),
                "--tantalum.claude.models=claude-sonnet-5-5,claude-sonnet-5,claude-opus-5-5",
                "--tantalum.openai-routes.local.display-name=本地",
                "--tantalum.openai-routes.local.base-url=http://127.0.0.1:1/secret-path/v1",
                "--tantalum.openai-routes.local.api-key-env=LOCAL_SECRET_VARIABLE",
                "--tantalum.openai-routes.local.model=qwen3.8",
                "--tantalum.openai-routes.local.thinking=chat-template",
                "--tantalum.openai-routes.local.context-window=131072");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private JsonNode models(String user) throws Exception {
        HttpResponse<String> response = server.get(user, "/api/models");
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> submit(String user, String session, String mode, String model) throws Exception {
        String body = "{\"requestId\":\"r-" + System.nanoTime() + "\",\"mode\":\"" + mode + "\",\"text\":\"hi\""
                + (model == null ? "" : ",\"model\":" + model) + "}";
        return server.post(user, "/api/sessions/" + session + "/submissions", body);
    }

    private HttpResponse<String> putChoice(String user, String body) throws Exception {
        return server.http.send(server.request(user, "/api/settings/model").header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 接口不写为 null 的项。 */
    private static boolean absent(JsonNode node) {
        return node.isNull() || node.isMissingNode();
    }

    /** 错误的代码和参数（console-i18n I9）；诊断是英文，不在这里比。 */
    private static String error(HttpResponse<String> response) {
        JsonNode body = JSON.readTree(response.body());
        return body.path("code").asString() + (body.has("params") ? " " + body.get("params") : "");
    }

    private void awaitRuns(String session, int count) throws Exception {
        String body = "";
        for (int i = 0; i < 500; i++) {
            body = server.get("alice", "/api/sessions/" + session + "/runs").body();
            if (body.split("\"endReason\"", -1).length - 1 >= count) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("runs did not end: " + body);
    }

    /** 需求 2、1.6：按路线列出模型和档位，不返回地址、密钥变量名；默认是管理员的。 */
    @Test
    void theListHasRoutesAndModelsButNoAddressesOrKeyVariables() throws Exception {
        HttpResponse<String> raw = server.get("alice", "/api/models");
        for (String hidden : List.of("127.0.0.1", "secret-path", "LOCAL_SECRET_VARIABLE", "ANTHROPIC_API_KEY", "baseUrl", "base-url", "apiKeyEnv")) {
            assertFalse(raw.body().contains(hidden), hidden + " leaked: " + raw.body());
        }
        JsonNode models = JSON.readTree(raw.body());
        assertEquals("claude", models.path("routes").get(0).path("name").asString());
        assertEquals(List.of("claude-sonnet-5-5", "claude-sonnet-5", "claude-opus-5-5"),
                models.path("routes").get(0).path("models").valueStream().map(model -> model.path("id").asString()).toList());
        JsonNode local = models.path("routes").get(1);
        assertEquals("本地", local.path("displayName").asString());
        assertFalse(local.path("cloud").asBoolean());
        JsonNode qwen = local.path("models").get(0);
        assertEquals("[\"off\",\"on\"]", qwen.path("levels").toString());
        assertEquals("on", qwen.path("defaultLevel").asString());
        assertFalse(qwen.path("vision").asBoolean());
        assertEquals(131_072, qwen.path("window").asLong());
        assertEquals(4, qwen.path("points").size());
        assertEquals("{\"route\":\"claude\",\"model\":\"claude-sonnet-5-5\",\"effort\":\"medium\"}", models.path("default").toString());
        assertTrue(absent(models.path("mine")), "nobody chose yet");
    }

    /** 需求 5.6、M11：不可用的选择回 400，写明是哪一样；这些提交不进 journal。 */
    @Test
    void anUnusableChoiceIsRefusedAndNotRecorded() throws Exception {
        Map<String, String> refused = new java.util.LinkedHashMap<>();
        refused.put("{\"route\":\"kimi\",\"model\":\"k2\",\"effort\":null}", "model/route-unavailable {\"route\":\"kimi\"}");
        refused.put("{\"route\":\"claude\",\"model\":\"claude-opus-4-1\",\"effort\":\"high\"}", "model/not-offered {\"model\":\"claude-opus-4-1\"}");
        refused.put("{\"route\":\"local\",\"model\":\"qwen3.8\",\"effort\":\"high\"}", "model/level-unknown {\"level\":\"high\",\"levels\":[\"off\",\"on\"],\"model\":\"qwen3.8\"}");
        refused.put("{\"route\":\"local\",\"model\":\"qwen3.8\"}", "model/level-required {\"levels\":[\"off\",\"on\"],\"model\":\"qwen3.8\"}");
        refused.put("{\"route\":\"local\"}", "request/invalid");
        for (Map.Entry<String, String> each : refused.entrySet()) {
            HttpResponse<String> response = submit("alice", "s1", "prompt", each.getKey());
            assertEquals(400, response.statusCode(), each.getKey());
            assertEquals(each.getValue(), error(response), each.getKey());
        }
        assertEquals(404, server.get("alice", "/api/sessions/s1/messages").statusCode(), "nothing reached the journal");
        assertEquals(0, claudeCalls.size() + localCalls.size());
    }

    /** M11：会话一开始就关着一个工具，不支持会话中途工具变化的 Claude 模型不能选；支持的照常。 */
    @Test
    void aSessionWithToolChangesRefusesModelsWithoutThem() throws Exception {
        assertEquals(200, server.http.send(server.request("alice", "/api/plugins/tool/memory_write").header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"enabled\":false}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, submit("alice", "s2", "prompt", null).statusCode());
        awaitRuns("s2", 1);
        assertTrue(JSON.readTree(server.get("alice", "/api/sessions/s2/messages").body()).path("toolChangesInHistory").asBoolean());
        HttpResponse<String> sonnet5 = submit("alice", "s2", "prompt", "{\"route\":\"claude\",\"model\":\"claude-sonnet-5\",\"effort\":\"low\"}");
        assertEquals(400, sonnet5.statusCode());
        assertEquals("model/tool-changes {\"model\":\"claude-sonnet-5\"}", error(sonnet5));
        assertEquals(200, submit("alice", "s2", "prompt", "{\"route\":\"claude\",\"model\":\"claude-opus-5-5\",\"effort\":\"low\"}").statusCode());
    }

    /** 需求 5.1、M13：带的选择进 UserPrompt，调到对的路线；会话视图带着现在的选择。插话带了也不进 journal。 */
    @Test
    void anAcceptedChoiceIsRecordedAndASteerIgnoresIt() throws Exception {
        assertEquals(200, submit("alice", "s3", "prompt", "{\"route\":\"local\",\"model\":\"qwen3.8\",\"effort\":\"off\"}").statusCode());
        awaitRuns("s3", 1);
        assertEquals(new ModelChoice("local", "qwen3.8", "off"), localCalls.getLast().model());
        JsonNode view = JSON.readTree(server.get("alice", "/api/sessions/s3/messages").body());
        assertEquals("{\"route\":\"local\",\"model\":\"qwen3.8\",\"effort\":\"off\"}", view.path("model").toString());

        assertEquals(200, submit("alice", "s3", "steer", "{\"route\":\"claude\",\"model\":\"claude-opus-5-5\",\"effort\":\"max\"}").statusCode());
        awaitRuns("s3", 2);
        assertEquals(new ModelChoice("local", "qwen3.8", "off"), localCalls.getLast().model(), "a steer does not switch models");
        // 需求 8.4：换了选择（这里只换档位）的那一轮有一条 modelChange；没换的那轮（插话开的）没有
        assertEquals(200, submit("alice", "s3", "prompt", "{\"route\":\"local\",\"model\":\"qwen3.8\",\"effort\":\"on\"}").statusCode());
        awaitRuns("s3", 3);
        List<JsonNode> changes = JSON.readTree(server.get("alice", "/api/sessions/s3/messages").body()).path("items").valueStream()
                .filter(item -> item.path("kind").asString().equals("modelChange")).toList();
        assertEquals(1, changes.size(), changes.toString());
        assertEquals("local", changes.getFirst().path("route").asString());
        assertEquals("on", changes.getFirst().path("effort").asString());

        Path file = new SessionPaths(temp.resolve("data")).sessionFile(new Principal("alice"), new SessionId("s3"));
        String steer = Files.readAllLines(file).stream().filter(line -> line.contains("\"type\":\"SteerSubmitted\"")).findFirst().orElseThrow();
        assertFalse(steer.contains("claude-opus-5-5"), steer);
    }

    /** 需求 4.1–4.4：上次的选择每个用户一份；不可用了 mine 为空；新会话用它。 */
    @Test
    void theLastChoiceIsPerUser() throws Exception {
        HttpResponse<String> saved = putChoice("alice", "{\"route\":\"claude\",\"model\":\"claude-opus-5-5\",\"effort\":\"high\"}");
        assertEquals(200, saved.statusCode(), saved.body());
        String opus = "{\"route\":\"claude\",\"model\":\"claude-opus-5-5\",\"effort\":\"high\"}";
        assertEquals(opus, JSON.readTree(saved.body()).path("model").toString());
        assertEquals(opus, models("alice").path("mine").toString());
        assertTrue(absent(models("bob").path("mine")), "bob is not affected");

        // 新会话没带选择：用 alice 上次的
        assertEquals(200, submit("alice", "s4", "prompt", null).statusCode());
        awaitRuns("s4", 1);
        assertEquals(new ModelChoice("claude", "claude-opus-5-5", "high"), claudeCalls.getLast().model());

        assertEquals(400, putChoice("alice", "{\"route\":\"local\",\"model\":\"qwen3.8\",\"effort\":\"max\"}").statusCode());
        assertEquals(400, putChoice("alice", "not json").statusCode());
        server.context.getBean(Engine.class).userSettings().setModelChoice(new Principal("alice"), new ModelChoice("claude", "claude-gone", "low"));
        assertTrue(absent(models("alice").path("mine")), "a choice that is gone is not offered");
    }

    @Test
    void signedOutGetsNothing() throws Exception {
        assertEquals(401, server.get(null, "/api/models").statusCode());
        assertEquals(401, putChoice(null, "{\"route\":\"claude\",\"model\":\"claude-opus-5-5\",\"effort\":\"high\"}").statusCode());
    }

    private static long p95(List<Long> nanos) {
        List<Long> sorted = nanos.stream().sorted().toList();
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    }

    /**
     * 任务 18（NFR Performance）：200 个模型时 {@code GET /api/models} 100 次的 P95 ≤ 50 ms（从内存返回；这里量的是含 HTTP 往返的
     * 时间，比服务器处理时间长）；提交带不带 {@code model} 的 P95 差 ≤ 5 ms。结果打印出来，记进 tech.md。
     */
    @Test
    void listingAndSubmittingStayFast() throws Exception {
        StringBuilder allow = new StringBuilder("qwen3.8");
        for (int i = 1; i < 200; i++) {
            allow.append(",model-").append(i);
        }
        Map<String, ModelClient> clients = Map.of("claude", answering(claudeCalls), "local", answering(localCalls));
        try (TestServer many = new TestServer(temp.resolve("data-many"), temp.resolve("ws-many"), answering(claudeCalls),
                context -> context.registerBean(RouteClients.class, () -> new RouteClients(clients)),
                "--tantalum.openai-routes.local.base-url=http://127.0.0.1:1/v1",
                "--tantalum.openai-routes.local.model=qwen3.8",
                "--tantalum.openai-routes.local.models=" + allow,
                "--tantalum.openai-routes.local.context-window=131072")) {
            assertEquals(200, JSON.readTree(many.get("alice", "/api/models").body()).path("routes").get(1).path("models").size());
            for (int i = 0; i < 20; i++) {
                many.get("alice", "/api/models");
            }
            List<Long> listing = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                long started = System.nanoTime();
                assertEquals(200, many.get("alice", "/api/models").statusCode());
                listing.add(System.nanoTime() - started);
            }
            List<Long> plain = new ArrayList<>();
            List<Long> chosen = new ArrayList<>();
            String qwen = "{\"route\":\"local\",\"model\":\"qwen3.8\",\"effort\":null}";
            for (int i = 0; i < 120; i++) {
                for (boolean withModel : new boolean[] {false, true}) {
                    String body = "{\"requestId\":\"p" + i + withModel + "\",\"mode\":\"prompt\",\"text\":\"hi\""
                            + (withModel ? ",\"model\":" + qwen : "") + "}";
                    long started = System.nanoTime();
                    HttpResponse<String> response = many.post("alice", "/api/sessions/perf-" + i + withModel + "/submissions", body);
                    long took = System.nanoTime() - started;
                    assertEquals(200, response.statusCode(), response.body());
                    if (i >= 20) {
                        (withModel ? chosen : plain).add(took);
                    }
                }
            }
            double listingMs = p95(listing) / 1e6;
            double plainMs = p95(plain) / 1e6;
            double chosenMs = p95(chosen) / 1e6;
            System.out.printf("NFR /api/models with 200 models: P95 %.2f ms; submit P95 without model %.2f ms, with model %.2f ms%n",
                    listingMs, plainMs, chosenMs);
            assertTrue(listingMs <= 50, "GET /api/models P95 " + listingMs + " ms");
            assertTrue(chosenMs - plainMs <= 5, "submitting with a model costs " + (chosenMs - plainMs) + " ms more at P95");
        }
    }
}
