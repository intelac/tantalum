package tantalum.shell.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tantalum.shell.model.OpenAiThinking;
import tantalum.shell.net.LogCapture;

/** model-picker 任务 5（需求 2.2、2.5、2.7）：OpenAI 兼容接口的模型列表和窗口。本地替身服务，不访问外网。 */
class OpenAiModelsTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger hits = new AtomicInteger();
    private final List<String> authorization = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            hits.incrementAndGet();
            String auth = exchange.getRequestHeaders().getFirst("authorization");
            authorization.add(auth == null ? "-" : auth);
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
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

    private RouteSettings route(List<String> allow, boolean listModels, long contextWindow, Map<String, RouteSettings.Override> overrides) {
        return new RouteSettings("local", "本地", RouteSettings.Kind.OPENAI, null, "qwen3.8-flash-next-unsloth-ud-q4_k_xl", allow, 16384,
                contextWindow, 1.75, 1, Duration.ofSeconds(300), Duration.ofSeconds(60), null, false,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", listModels, OpenAiThinking.CHAT_TEMPLATE, null, false, overrides);
    }

    private List<ModelInfo> read(RouteSettings route, String key) throws Exception {
        return new OpenAiModels(route, key, Duration.ofSeconds(5)).read();
    }

    @Test
    void llamaCppReportsItsWindowInMeta() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/route/llamacpp-models.json")) {
            body.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        List<ModelInfo> models = read(route(List.of(), true, 0, Map.of()), null);
        assertEquals(1, models.size());
        ModelInfo qwen = models.getFirst();
        assertEquals("qwen3.8-flash-next-unsloth-ud-q4_k_xl", qwen.id());
        assertEquals(131_072, qwen.window());
        assertEquals(ModelInfo.Source.MODEL, qwen.windowSource());
        assertEquals(new Levels(List.of("off", "on"), "on"), qwen.levels());
        assertTrue(qwen.toolChanges());
        assertEquals(List.of("-"), authorization, "no key: no authorization header");
    }

    @Test
    void vllmAndLmStudioFieldsAndTheConfiguredFallback() throws Exception {
        body.set("{\"data\":[{\"id\":\"vllm\",\"max_model_len\":32768},{\"id\":\"lmstudio\",\"context_length\":8192},{\"id\":\"plain\"}]}");
        List<ModelInfo> models = read(route(List.of(), true, 4096, Map.of()), "test-key");
        assertEquals(List.of(32_768L, 8_192L, 4_096L), models.stream().map(ModelInfo::window).toList());
        assertEquals(ModelInfo.Source.CONFIG, models.get(2).windowSource(), "neither field: the route's context-window");
        assertEquals(List.of("Bearer test-key"), authorization);
    }

    @Test
    void anOverrideWinsOverTheReportedWindowAndThinking() throws Exception {
        body.set("{\"data\":[{\"id\":\"moonshotai/kimi-k2\",\"context_length\":8192},{\"id\":\"qwen\"}]}");
        List<ModelInfo> models = read(route(List.of(), true, 0, Map.of("moonshotai/kimi-k2",
                new RouteSettings.Override(OpenAiThinking.REASONING_EFFORT, null, "high", true, 262_144L))), null);
        ModelInfo kimi = models.getFirst();
        assertEquals(262_144, kimi.window());
        assertEquals(ModelInfo.Source.CONFIG, kimi.windowSource());
        assertEquals(new Levels(List.of("low", "medium", "high"), "high"), kimi.levels());
        assertTrue(kimi.vision());
        assertFalse(models.get(1).vision(), "the route's vision");
    }

    @Test
    void theAllowlistFiltersAndWithoutListingNoRequestIsSent() throws Exception {
        body.set("{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"},{\"id\":\"c\"}]}");
        try (LogCapture logs = new LogCapture()) {
            assertEquals(List.of("a", "c"), read(route(List.of("c", "a", "z"), true, 0, Map.of()), null).stream().map(ModelInfo::id).toList());
            assertTrue(logs.all().contains("z"), logs.all());
        }
        hits.set(0);
        assertEquals(List.of("x", "y"), read(route(List.of("x", "y"), false, 8000, Map.of()), null).stream().map(ModelInfo::id).toList());
        assertEquals(List.of("qwen3.8-flash-next-unsloth-ud-q4_k_xl"),
                read(route(List.of(), false, 8000, Map.of()), null).stream().map(ModelInfo::id).toList(), "no allowlist: the default model");
        assertEquals(0, hits.get(), "list-models=false sends nothing");
    }

    @Test
    void errorsCarryOnlyACategoryAndNoAddressOrKey() throws Exception {
        body.set("{\"error\":\"bad key\"}");
        status.set(401);
        try (LogCapture logs = new LogCapture()) {
            ModelList.Unreadable failure = assertThrows(ModelList.Unreadable.class, () -> read(route(List.of(), true, 0, Map.of()), "secret-key"));
            assertEquals("401", failure.getMessage());
            assertFalse(logs.all().contains("/v1/models") || logs.all().contains("secret-key") || logs.all().contains("Bearer"), logs.all());
        }
        status.set(200);
        body.set("<html>");
        assertEquals("返回的不是模型列表",
                assertThrows(ModelList.Unreadable.class, () -> read(route(List.of(), true, 0, Map.of()), null)).getMessage());
        RouteSettings route = route(List.of(), true, 0, Map.of());
        server.stop(0);
        assertEquals("连不上", assertThrows(ModelList.Unreadable.class, () -> read(route, null)).getMessage());
    }
}
