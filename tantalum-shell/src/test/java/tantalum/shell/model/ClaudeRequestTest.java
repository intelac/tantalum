package tantalum.shell.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.handler.CallLimiter;
import tantalum.shell.handler.ModelHandler;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.json.JsonText;
import tantalum.shell.journal.SessionFile;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.command.CallModel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.RequestOptions;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.model.ToolSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 发给 Claude 的请求体和 beta 头必须逐字节不变：期望文件是把组请求的代码移进 {@code ClaudeRequest} 之前取的样。
 * 有意改请求时用 {@code -Dclaude.record=true} 重新生成，并在提交里说明。
 */
class ClaudeRequestTest {

    private static final Path EXPECTED = Path.of("src/test/resources/model/claude-requests.txt");
    private static final String REPLY = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"type\":\"message\","
            + "\"role\":\"assistant\",\"model\":\"claude-sonnet-5-5\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,"
            + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
            + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
            + "\"usage\":{\"output_tokens\":1}}\n\n"
            + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
    private static final ChunkListener IGNORE = new ChunkListener() {
        @Override public void onText(String text) { }
        @Override public void onReasoning(String text) { }
    };

    private FakeServer server;

    @BeforeEach
    void start() throws Exception {
        server = new FakeServer();
        server.body = REPLY;
    }

    @AfterEach
    void stop() {
        server.close();
    }

    /** 一个示例：设置加 prompt。 */
    private record Sample(String name, ClaudeClient.Settings settings, Prompt prompt) {}

    private static ClaudeClient.Settings settings(long maxTokens, String effort, boolean fallbacks, boolean strictThinking) {
        return new ClaudeClient.Settings("claude-sonnet-5-5", maxTokens, effort, fallbacks, Duration.ofSeconds(5), Duration.ofSeconds(5),
                OverflowRules.defaults(), strictThinking);
    }

    private ClaudeClient client(ClaudeClient.Settings settings) {
        return new ClaudeClient(AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(server.url()).maxRetries(0).build(), settings);
    }

    /** 有工具的多轮历史：自己的思考和签名、被遮蔽的思考、别家的推理、文字、工具调用（含无效参数）、工具结果和插话。 */
    private static Prompt history(Prompt.Output output) {
        Map<String, Json> thinking = new LinkedHashMap<>();
        thinking.put("type", new Json.Str("thinking"));
        thinking.put("thinking", new Json.Str("hmm"));
        thinking.put("signature", new Json.Str("SIG123"));
        Map<String, Json> redacted = new LinkedHashMap<>();
        redacted.put("type", new Json.Str("redacted_thinking"));
        redacted.put("data", new Json.Str("ENC"));
        Map<String, Json> properties = new LinkedHashMap<>();
        properties.put("path", new Json.Obj(Map.of("type", new Json.Str("string"))));
        properties.put("limit", new Json.Obj(Map.of("type", new Json.Str("integer"))));
        Map<String, Json> schema = new LinkedHashMap<>();
        schema.put("type", new Json.Str("object"));
        schema.put("properties", new Json.Obj(properties));
        schema.put("required", new Json.Arr(List.of(new Json.Str("path"))));
        List<ContentBlock> assistant = List.of(
                new ReasoningBlock("claude", new Json.Obj(thinking)),
                new ReasoningBlock("claude", new Json.Obj(redacted)),
                new ReasoningBlock("openai", new Json.Str("not mine")),
                new TextBlock("calling"),
                new TextBlock(""),
                new ToolCallBlock("t1", "read_file", "{\"path\":\"a\"}", new Json.Obj(Map.of("path", new Json.Str("a")))),
                new ToolCallBlock("t2", "grep", "{oops", null));
        return new Prompt("you are helpful", List.of(new ToolSpec("read_file", "read a file", new Json.Obj(schema)),
                new ToolSpec("grep", "search", new Json.Obj(Map.of("type", new Json.Str("object"))))), List.of(
                Message.user("q"),
                new Message(Role.ASSISTANT, assistant, false),
                new Message(Role.USER, List.of(new ToolResultBlock("t1", "body", false, null),
                        new ToolResultBlock("t2", "bad arguments", true, null), new TextBlock("steer")), false)), output);
    }

    private static List<Sample> samples() {
        return List.of(
                new Sample("plain, fallbacks", settings(64000, "medium", true, false),
                        new Prompt("sys", List.of(), List.of(Message.user("hi")))),
                new Sample("tools and history, strict thinking, no fallbacks", settings(8192, "high", false, true),
                        history(Prompt.Output.DEFAULT)),
                new Sample("summary output, fallbacks and strict thinking", settings(8192, "low", true, true),
                        history(new Prompt.Output(false, 2048))),
                new Sample("blank system, no betas", settings(4096, "max", false, false),
                        new Prompt("", List.of(), List.of(Message.user("hi"), new Message(Role.ASSISTANT, List.of(new TextBlock("")), false),
                                Message.user("again")))));
    }

    private String sent(Sample sample) {
        client(sample.settings()).call(sample.prompt(), IGNORE);
        int last = server.requests.size() - 1;
        List<String> betas = server.requestHeaders.get(last).getOrDefault("anthropic-beta", List.of());
        return "== " + sample.name() + "\nanthropic-beta: " + String.join(",", betas) + "\n" + server.requests.get(last) + "\n";
    }

    @Test
    void requestBodiesAreUnchanged() throws IOException {
        StringBuilder actual = new StringBuilder();
        for (Sample sample : samples()) {
            actual.append(sent(sample));
        }
        if (Boolean.getBoolean("claude.record")) {
            Files.createDirectories(EXPECTED.getParent());
            Files.writeString(EXPECTED, actual, StandardCharsets.UTF_8);
        }
        assertEquals(Files.readString(EXPECTED, StandardCharsets.UTF_8), actual.toString(), "request changed; see the class comment");
    }

    // ---- 选项：记下的就是发出的，且不含密钥和地址 ----

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 选项里每一项都和请求体（beta 是请求头）一致；请求体里的这些键也都在选项里。 */
    @Test
    void optionsAreWhatIsSent() {
        for (Sample sample : samples()) {
            ClaudeClient client = client(sample.settings());
            RequestOptions options = client.options(sample.prompt());
            client.call(sample.prompt(), IGNORE);
            JsonNode body = MAPPER.readTree(server.requests.getLast());
            String header = String.join(",", server.requestHeaders.getLast().getOrDefault("anthropic-beta", List.of()));
            Map<String, Json> extra = ((Json.Obj) options.extra()).fields();

            assertEquals("claude", options.provider(), sample.name());
            assertEquals(body.path("model").asString(), options.model(), sample.name());
            assertEquals(body.path("max_tokens").asLong(), options.maxTokens(), sample.name());
            assertEquals(body.path("tool_choice").path("type").asString().equals("none"), !options.toolsAllowed(), sample.name());
            for (String key : List.of("thinking", "output_config", "fallbacks")) {
                assertEquals(body.has(key), extra.containsKey(key), sample.name() + ": " + key);
                if (extra.containsKey(key)) {
                    assertEquals(body.get(key), MAPPER.readTree(JsonText.toText(extra.get(key))), sample.name() + ": " + key);
                }
            }
            String betas = extra.get("betas") instanceof Json.Arr list
                    ? String.join(",", list.items().stream().map(beta -> ((Json.Str) beta).value()).toList()) : "";
            assertEquals(header, betas, sample.name());
        }
    }

    @Test
    void optionsFollowTheSettingsAndTheOutputLimit() {
        RequestOptions summary = client(settings(8192, "low", true, true)).options(history(new Prompt.Output(false, 2048)));
        assertEquals(2048, summary.maxTokens());
        assertFalse(summary.toolsAllowed());
        assertEquals("{\"thinking\":{\"type\":\"adaptive\",\"display\":\"summarized\",\"block_binding\":{\"prefix_mismatch_behavior\":\"error\"}},"
                + "\"output_config\":{\"effort\":\"low\"},\"betas\":[\"server-side-fallback-2026-07-01\",\"thinking-binding-controls-2026-08-01\"],"
                + "\"fallbacks\":\"default\"}", JsonText.toText(summary.extra()));

        RequestOptions plain = client(settings(4096, "max", false, false)).options(new Prompt("", List.of(), List.of(Message.user("hi"))));
        assertEquals(new RequestOptions("claude", "claude-sonnet-5-5", 4096, true, plain.extra()), plain);
        assertEquals("{\"thinking\":{\"type\":\"adaptive\",\"display\":\"summarized\"},\"output_config\":{\"effort\":\"max\"}}",
                JsonText.toText(plain.extra()));
    }

    /**
     * 给客户端一个假密钥，走真实路径：处理器调客户端，结算事件写进会话文件。密钥确实在请求头里发出去了，
     * 但不在选项、事件和 journal 行里；服务地址也不在。
     */
    @Test
    void theKeyNeverReachesOptionsOrTheJournal(@TempDir Path dir) throws Exception {
        String key = "sk-ant-fake-" + "q8Zr2Lw7";
        ClaudeClient client = new ClaudeClient(AnthropicOkHttpClient.builder().apiKey(key).baseUrl(server.url()).maxRetries(0).build(),
                settings(8192, "high", true, true));
        RunRef run = new RunRef(new Principal("alice"), new SessionId("s1"), new RunId("r1"));
        ModelReplied reply = (ModelReplied) new ModelHandler(client, new CallLimiter(1), Duration.ofSeconds(5), ignored -> IGNORE,
                Clock.systemUTC()).handle(new CallModel(new CommandId("c1"), run, history(Prompt.Output.DEFAULT)));

        assertEquals(client.options(history(Prompt.Output.DEFAULT)), reply.options());
        // SDK 放在哪个请求头由它决定，这里只要求密钥确实随请求发出，下面的“找不到”才有意义。
        assertTrue(server.requestHeaders.getLast().values().stream().flatMap(List::stream).anyMatch(value -> value.contains(key)),
                "the fake key must really be sent");

        EventCodec codec = EventCodec.withBuiltins();
        String options = codec.mapper().writeValueAsString(reply.options());
        try (SessionFile file = SessionFile.open(new SessionPaths(dir), run.principal(), run.session(), codec)) {
            file.append(List.of(new EventEnvelope(1, Instant.parse("2026-11-03T00:00:00Z"), run.principal(), run.session(), run.run(), reply)));
        }
        String journal = Files.readString(new SessionPaths(dir).sessionFile(run.principal(), run.session()), StandardCharsets.UTF_8);

        assertTrue(journal.contains("\"options\":" + options), "the options are in the journal line");
        for (String text : List.of(options, codec.encode(reply), journal)) {
            assertFalse(text.contains(key), "key leaked");
            assertFalse(text.contains("q8Zr2Lw7"), "key leaked");
            assertFalse(text.contains(server.url()), "address leaked");
        }
    }
}
