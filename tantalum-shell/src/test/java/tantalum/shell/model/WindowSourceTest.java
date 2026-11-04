package tantalum.shell.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** compaction-threshold T1、T3：从模型那边读窗口。本地替身服务按路径回答，不访问外网。 */
class WindowSourceTest {

    private record Answer(int status, String body) {}

    private HttpServer server;
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<String> betas = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String beta = exchange.getRequestHeaders().getFirst("anthropic-beta");
            betas.add(exchange.getRequestURI().getPath() + " " + (beta == null ? "-" : beta));
            Answer answer = answers.getOrDefault(exchange.getRequestURI().getPath(), new Answer(404, "{\"type\":\"error\"}"));
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(answer.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void answer(String path, String body) {
        answers.put(path, new Answer(200, body));
    }

    private static String model(String id, long window, String extra) {
        return "{\"type\":\"model\",\"id\":\"" + id + "\",\"display_name\":\"" + id + "\",\"created_at\":\"2026-01-01T00:00:00Z\","
                + "\"max_input_tokens\":" + window + ",\"max_tokens\":128000" + extra + "}";
    }

    private ClaudeWindow claude(boolean fallbacks) {
        AnthropicClient sdk = AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(url()).maxRetries(0).build();
        return new ClaudeWindow(sdk, "claude-sonnet-5-5", fallbacks, Duration.ofSeconds(5));
    }

    private OpenAiWindow openai(String model) {
        return new OpenAiWindow(url() + "/v1/", model, null, Duration.ofSeconds(5));
    }

    // ---- Claude ----

    @Test
    void claudeReadsMaxInputTokens() throws Exception {
        answer("/v1/models/claude-sonnet-5-5", model("claude-sonnet-5-5", 1_000_000, ""));
        assertEquals(OptionalLong.of(1_000_000), claude(false).read());
        assertEquals(List.of("/v1/models/claude-sonnet-5-5 -"), betas, "no fallback header without fallbacks");
    }

    @Test
    void withFallbacksTheSmallestWindowWins() throws Exception {
        answer("/v1/models/claude-sonnet-5-5", model("claude-sonnet-5-5", 1_000_000,
                ",\"allowed_fallback_models\":[\"claude-sonnet-5\",\"claude-haiku-4-5\"]"));
        answer("/v1/models/claude-sonnet-5", model("claude-sonnet-5", 1_000_000, ""));
        answer("/v1/models/claude-haiku-4-5", model("claude-haiku-4-5", 200_000, ""));
        assertEquals(OptionalLong.of(200_000), claude(true).read());
        assertTrue(betas.getFirst().endsWith(ClaudeWindow.FALLBACK_LIST_BETA), betas.toString());
    }

    @Test
    void anUnreadableFallbackLeavesTheMainWindow() throws Exception {
        answer("/v1/models/claude-sonnet-5-5", model("claude-sonnet-5-5", 1_000_000, ",\"allowed_fallback_models\":[\"gone\"]"));
        assertEquals(OptionalLong.of(1_000_000), claude(true).read());
    }

    @Test
    void claudeWithoutMaxInputTokensIsUnknown() throws Exception {
        answer("/v1/models/claude-sonnet-5-5", "{\"type\":\"model\",\"id\":\"claude-sonnet-5-5\",\"display_name\":\"x\","
                + "\"created_at\":\"2026-01-01T00:00:00Z\"}");
        assertEquals(OptionalLong.empty(), claude(false).read());
    }

    @Test
    void claudeHttpErrorsAreThrown() {
        answers.put("/v1/models/claude-sonnet-5-5", new Answer(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\"}}"));
        assertThrows(RuntimeException.class, () -> claude(false).read());
    }

    // ---- OpenAI 兼容 ----

    @Test
    void llamaCppReportsNCtx() throws Exception {
        answer("/v1/models", "{\"object\":\"list\",\"data\":[{\"id\":\"other\",\"meta\":{\"n_ctx\":4096}},"
                + "{\"id\":\"qwen\",\"object\":\"model\",\"meta\":{\"n_ctx\":131072}}]}");
        assertEquals(OptionalLong.of(131_072), openai("qwen").read());
    }

    @Test
    void vllmAndOthersAreReadInOrder() throws Exception {
        answer("/v1/models", "{\"data\":[{\"id\":\"a\",\"max_model_len\":32768,\"context_length\":8192},"
                + "{\"id\":\"b\",\"context_length\":8192},{\"id\":\"c\",\"meta\":{\"n_ctx\":0},\"max_model_len\":\"big\"}]}");
        assertEquals(OptionalLong.of(32_768), openai("a").read());
        assertEquals(OptionalLong.of(8_192), openai("b").read());
        assertEquals(OptionalLong.empty(), openai("c").read(), "zero and strings are not windows");
    }

    @Test
    void anotherModelNameIsUnknown() throws Exception {
        answer("/v1/models", "{\"data\":[{\"id\":\"qwen\",\"meta\":{\"n_ctx\":131072}}]}");
        assertEquals(OptionalLong.empty(), openai("llama").read());
    }

    @Test
    void openAiErrorsAreThrown() {
        assertThrows(IOException.class, () -> openai("qwen").read(), "404");
        answer("/v1/models", "<html>");
        assertThrows(IOException.class, () -> openai("qwen").read(), "not JSON");
    }
}
