package tantalum.shell.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tantalum.shell.net.LogCapture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * model-picker 任务 4（需求 2.2、2.3、2.5、2.6）：假的 Models 接口返回 2026-11-06 实测的列表（{@code route/claude-models.json}，
 * 14 个模型，不含密钥），不访问外网。
 */
class ClaudeModelsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private JsonNode recorded;
    /** 路径（含查询串）→ 回答；没有的回 404。 */
    private final Map<String, Function<String, String>> answers = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/route/claude-models.json")) {
            recorded = JSON.readTree(in);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            requests.add(exchange.getRequestURI().getPath() + (query == null ? "" : "?" + query));
            Function<String, String> answer = answers.get(exchange.getRequestURI().getPath());
            String body = answer == null ? null : answer.apply(query == null ? "" : query);
            byte[] bytes = (body == null ? "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\"}}" : body)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        answers.put("/v1/models", query -> recorded.toString());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ClaudeModels models(List<String> allow, boolean fallbacks) {
        return new ClaudeModels(AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .maxRetries(0).build(), "claude", allow, "medium", fallbacks, 64_000, Duration.ofSeconds(5));
    }

    private static ModelInfo find(List<ModelInfo> models, String id) {
        return models.stream().filter(model -> model.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError(id));
    }

    @Test
    void onlyModelsWithAdaptiveThinkingAreOfferedAndTheRestAreLogged() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            List<ModelInfo> models = models(List.of(), false).read();
            assertEquals(11, models.size(), "14 listed, 3 only take budget_tokens");
            for (String old : List.of("claude-opus-4-5-20251101", "claude-haiku-4-5-20251001", "claude-sonnet-4-5-20250929")) {
                assertFalse(models.stream().anyMatch(model -> model.id().equals(old)), old);
                assertTrue(logs.all().contains(old), logs.all());
            }
            assertTrue(logs.all().contains("no adaptive thinking"), logs.all());
        }
    }

    @Test
    void eachModelCarriesItsWindowCapVisionLevelsAndToolChanges() throws Exception {
        List<ModelInfo> models = models(List.of(), false).read();
        ModelInfo sonnet = find(models, "claude-sonnet-5-5");
        assertEquals("Claude Sonnet 5.5", sonnet.name());
        assertEquals(1_000_000, sonnet.window());
        assertEquals(ModelInfo.Source.MODEL, sonnet.windowSource());
        assertEquals(64_000, sonnet.maxTokens(), "the route's max-tokens is smaller than the model's 128000");
        assertTrue(sonnet.vision());
        assertEquals(new Levels(List.of("low", "medium", "high", "xhigh", "max"), "medium"), sonnet.levels());
        assertTrue(sonnet.toolChanges());
        assertEquals(List.of("low", "medium", "high", "max"), find(models, "claude-sonnet-4-6").levels().names());
        assertFalse(find(models, "claude-haiku-5-5").toolChanges(), "Haiku 5.5 cannot change tools mid-session");
        assertEquals(List.of("/v1/models?limit=1000"), requests, "one page, no per-model requests without fallbacks");
    }

    @Test
    void theAllowlistKeepsOnlyItsModelsAndNamesTheMissingOne() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            List<ModelInfo> models = models(List.of("claude-opus-5-5", "claude-sonnet-5-5", "claude-sonnet-9"), false).read();
            assertEquals(List.of("claude-sonnet-5-5", "claude-opus-5-5"), models.stream().map(ModelInfo::id).toList());
            assertTrue(logs.all().contains("claude-sonnet-9"), logs.all());
        }
    }

    @Test
    void everyPageIsRead() throws Exception {
        ArrayNode all = (ArrayNode) recorded.get("data");
        ObjectNode first = (ObjectNode) recorded.deepCopy();
        ObjectNode second = (ObjectNode) recorded.deepCopy();
        first.set("data", JSON.createArrayNode().addAll(List.of(all.get(0), all.get(1))));
        first.put("has_more", true);
        first.put("last_id", all.get(1).get("id").asString());
        ArrayNode rest = JSON.createArrayNode();
        for (int i = 2; i < all.size(); i++) {
            rest.add(all.get(i));
        }
        second.set("data", rest);
        second.put("has_more", false);
        answers.put("/v1/models", query -> query.contains("after_id=") ? second.toString() : first.toString());
        assertEquals(11, models(List.of(), false).read().size());
        assertEquals(2, requests.size(), requests.toString());
        assertTrue(requests.get(1).contains("after_id=claude-sonnet-5-5"), requests.toString());
    }

    @Test
    void withFallbacksTheSmallestWindowOfTheFallbackModelsWins() throws Exception {
        answers.put("/v1/models/claude-sonnet-5-5", query -> "{\"type\":\"model\",\"id\":\"claude-sonnet-5-5\",\"display_name\":\"x\","
                + "\"created_at\":\"2026-01-01T00:00:00Z\",\"max_input_tokens\":1000000,"
                + "\"allowed_fallback_models\":[\"claude-sonnet-5\",\"claude-opus-4-5-20251101\"]}");
        List<ModelInfo> models = models(List.of("claude-sonnet-5-5"), true).read();
        assertEquals(200_000, find(models, "claude-sonnet-5-5").window(), "Opus 4.5 as a fallback has 200000");
    }

    @Test
    void failuresAreReportedByCategoryOnly() {
        answers.put("/v1/models", query -> null);
        ModelList.Unreadable missing = assertThrows(ModelList.Unreadable.class, () -> models(List.of(), false).read());
        assertEquals("404", missing.getMessage());
        server.stop(0);
        ModelList.Unreadable down = assertThrows(ModelList.Unreadable.class, () -> models(List.of(), false).read());
        assertEquals("连不上", down.getMessage());
    }
}
