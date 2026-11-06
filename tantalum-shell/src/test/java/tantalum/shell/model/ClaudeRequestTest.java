package tantalum.shell.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import tantalum.shell.attachment.AttachmentLimits;
import tantalum.shell.attachment.AttachmentStore;
import tantalum.shell.attachment.ImageNormalizer;
import tantalum.shell.attachment.RequestImages;
import tantalum.shell.attachment.StoredMedia;
import tantalum.shell.handler.CallLimiter;
import tantalum.shell.handler.ModelHandler;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.json.JsonText;
import tantalum.shell.journal.SessionFiles;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.AttachmentText;
import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;
import tantalum.value.command.CallModel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChangeBlock;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.Prompt;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.RequestOptions;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolChangeBlock;
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
        try (SessionFiles files = new SessionFiles(new SessionPaths(dir), codec, 4, (p, s, seq, offset) -> { })) {
            files.append(run.principal(), run.session(), List.of(tantalum.shell.journal.ResultWriter.noEffect(
                    new EventEnvelope(1, Instant.parse("2026-11-03T00:00:00Z"), run.principal(), run.session(), run.run(), reply))));
        }
        String journal = Files.readString(new SessionPaths(dir).sessionFile(run.principal(), run.session()), StandardCharsets.UTF_8);

        assertTrue(journal.contains("\"options\":" + options), "the options are in the journal line");
        for (String text : List.of(options, codec.encode(reply), journal)) {
            assertFalse(text.contains(key), "key leaked");
            assertFalse(text.contains("q8Zr2Lw7"), "key leaked");
            assertFalse(text.contains(server.url()), "address leaked");
        }
    }

    // ---- 附件（attachments 任务 15）：claude-attachment-requests.txt ----

    // ---- 会话中途换工具（core-tools 任务 19）----

    private static final Path TOOL_CHANGE_EXPECTED = Path.of("src/test/resources/model/claude-tool-change-requests.txt");

    private static ClaudeClient.Settings model(String model, boolean fallbacks) {
        return new ClaudeClient.Settings(model, 8192, "medium", fallbacks, Duration.ofSeconds(5), Duration.ofSeconds(5),
                OverflowRules.defaults(), true);
    }

    /** 会话开始时 write_file 没开（deferred），第二次提问时打开、grep 关掉。 */
    private static Prompt switched() {
        Json schema = new Json.Obj(Map.of("type", new Json.Str("object")));
        List<ToolSpec> tools = List.of(new ToolSpec("grep", "search", schema), new ToolSpec("read_file", "read", schema),
                new ToolSpec("write_file", "write", schema, true));
        return new Prompt("sys", tools, List.of(
                Message.user("q1"),
                new Message(Role.ASSISTANT, List.of(new TextBlock("a1")), false),
                Message.user("q2"),
                new Message(Role.SYSTEM, List.of(new ToolChangeBlock(List.of("write_file"), List.of("grep"))), false)));
    }

    /** 只有 deferred 的工具、还没有任何变化：照样带 beta，备用模型照样不带。 */
    private static Prompt deferredOnly() {
        Json schema = new Json.Obj(Map.of("type", new Json.Str("object")));
        return new Prompt("sys", List.of(new ToolSpec("read_file", "read", schema), new ToolSpec("write_file", "write", schema, true)),
                List.of(Message.user("q1")));
    }

    private static List<Sample> toolChangeSamples() {
        return List.of(
                new Sample("mid-session change on a model that supports it; fallbacks dropped", model("claude-sonnet-5-5", true), switched()),
                new Sample("deferred tool, no change yet; cache mark skips the deferred tool", model("claude-sonnet-5-5", false), deferredOnly()),
                new Sample("model without mid-session changes: deferred tool and the change are left out", model("claude-sonnet-5", true),
                        switched()));
    }

    @Test
    void toolChangeRequestBodies() throws IOException {
        StringBuilder actual = new StringBuilder();
        for (Sample sample : toolChangeSamples()) {
            actual.append(sent(sample));
        }
        if (Boolean.getBoolean("claude.record")) {
            Files.writeString(TOOL_CHANGE_EXPECTED, actual, StandardCharsets.UTF_8);
        }
        assertEquals(Files.readString(TOOL_CHANGE_EXPECTED, StandardCharsets.UTF_8), actual.toString(), "request changed; see the class comment");
    }

    @Test
    void toolChangesFollowTheModelAndDropFallbacks() {
        RequestOptions supported = new ClaudeRequest(model("claude-sonnet-5-5", true)).options(switched());
        String extra = supported.extra().toString();
        assertTrue(extra.contains(ClaudeRequest.TOOL_CHANGE_BETA), extra);
        assertFalse(extra.contains("fallback"), extra);
        RequestOptions unsupported = new ClaudeRequest(model("claude-sonnet-5", true)).options(switched());
        assertFalse(unsupported.extra().toString().contains(ClaudeRequest.TOOL_CHANGE_BETA));
        assertTrue(unsupported.extra().toString().contains("fallback"));
        RequestOptions plain = new ClaudeRequest(model("claude-sonnet-5-5", true)).options(new Prompt("sys", List.of(), List.of(Message.user("hi"))));
        assertFalse(plain.extra().toString().contains(ClaudeRequest.TOOL_CHANGE_BETA), "sessions without changes are unchanged");
    }

    private static final Path ATTACHMENT_EXPECTED = Path.of("src/test/resources/model/claude-attachment-requests.txt");
    private static final ImageRef PNG = new ImageRef("sha256:" + "a".repeat(64), "cat.png", "image/png", 3, 4, 3);
    private static final ImageRef SHOT = new ImageRef("sha256:" + "b".repeat(64), "shot.jpg", "image/jpeg", 3, 2048, 1024, 4096, 2048);
    private static final FileRef NOTES = new FileRef("sha256:" + "c".repeat(64), "notes.txt", "text/plain", 12, null);
    private static final FileRef PDF = new FileRef("sha256:" + "d".repeat(64), "report.pdf", "application/pdf", 9, 3);
    private static final FileRef LONG_PDF = new FileRef("sha256:" + "e".repeat(64), "book.pdf", "application/pdf", 9, 101);
    private static final FileRef HUGE_PDF = new FileRef("sha256:" + "f".repeat(64), "scan.pdf", "application/pdf", 32L * 1024 * 1024 + 1, 3);

    /** 内容固定的假附件：图片字节是 {@code IMG}，文件是 {@code %PDF-1}。只对 PDF 取文件内容，其余文件不该被读。 */
    private static final AttachmentMedia MEDIA = new AttachmentMedia() {
        @Override
        public Image image(ImageRef ref) {
            return new Image("IMG".getBytes(StandardCharsets.US_ASCII), ref.mediaType());
        }

        @Override
        public byte[] file(FileRef ref) {
            if (!ref.pdf()) {
                throw new AssertionError("only PDFs are read: " + ref.name());
            }
            return "%PDF-1".getBytes(StandardCharsets.US_ASCII);
        }
    };

    private static Message user(String text, AttachmentRef... refs) {
        return Message.user(text, List.of(refs));
    }

    private static List<Sample> attachmentSamples() {
        ClaudeClient.Settings plain = settings(4096, "medium", false, false);
        List<ContentBlock> calls = List.of(new ToolCallBlock("t1", "read_file", "{}", new Json.Obj(Map.of())),
                new ToolCallBlock("t2", "read_file", "{}", new Json.Obj(Map.of())));
        Message results = new Message(Role.USER, List.of(
                new ToolResultBlock("t1", AttachmentText.image(SHOT), false, null, List.of(SHOT)),
                new ToolResultBlock("t2", "could not read all of it", true, null, List.of(PNG))), false);
        return List.of(
                new Sample("text then an image; the image block takes the cache mark", plain,
                        new Prompt("sys", List.of(), List.of(user("what is this?", PNG)))),
                new Sample("attachments only: (see attached) first", plain,
                        new Prompt("sys", List.of(), List.of(user("", SHOT, NOTES)))),
                new Sample("a PDF also goes as a document; too long or too large ones only as text", plain,
                        new Prompt("sys", List.of(), List.of(user("summarize", PDF, LONG_PDF, HUGE_PDF)))),
                new Sample("tool results: images inside a good result, after an error result", plain,
                        new Prompt("sys", List.of(), List.of(Message.user("look"), new Message(Role.ASSISTANT, calls, false), results))),
                new Sample("an omitted image is only its text", plain,
                        new Prompt("sys", List.of(), List.of(new Message(Role.USER, List.of(new TextBlock("again"),
                                new TextBlock(AttachmentText.omitted(PNG))), false)))));
    }

    @Test
    void attachmentRequestBodies() throws IOException {
        StringBuilder actual = new StringBuilder();
        for (Sample sample : attachmentSamples()) {
            client(sample.settings()).call(sample.prompt(), MEDIA, IGNORE);
            int last = server.requests.size() - 1;
            actual.append("== ").append(sample.name()).append('\n').append(server.requests.get(last)).append('\n');
        }
        if (Boolean.getBoolean("claude.record")) {
            Files.writeString(ATTACHMENT_EXPECTED, actual, StandardCharsets.UTF_8);
        }
        assertEquals(Files.readString(ATTACHMENT_EXPECTED, StandardCharsets.UTF_8), actual.toString(), "request changed; see the class comment");
    }

    /** 附件被删了：这次调用失败、不重试，原因写明附件 ID；请求根本没有发出去（需求 4.2）。 */
    @Test
    void aDeletedAttachmentFailsTheCallWithItsId(@TempDir Path dir) throws Exception {
        AttachmentLimits limits = AttachmentLimits.defaults();
        AttachmentStore store = new AttachmentStore(dir, limits, new ImageNormalizer(limits));
        Principal alice = new Principal("alice");
        AttachmentRef notes = store.upload(alice, "notes.pdf", null,
                new java.io.ByteArrayInputStream("%PDF-1.4 not really".getBytes(StandardCharsets.US_ASCII)), -1);
        FileRef pdf = new FileRef(notes.id(), "notes.pdf", "application/pdf", notes.bytes(), 1);
        Files.delete(store.find(alice, notes.id()).orElseThrow().file());
        RunRef run = new RunRef(alice, new SessionId("s1"), new RunId("r1"));
        int before = server.requests.size();
        ModelHandler handler = new ModelHandler(client(settings(4096, "medium", false, false)), new CallLimiter(1), Duration.ofSeconds(5),
                ignored -> IGNORE, Clock.systemUTC(), ref -> new StoredMedia(store, new RequestImages(store), ref.principal(), ref.session()));
        ModelFailed failed = (ModelFailed) handler.handle(new CallModel(new CommandId("c1"), run,
                new Prompt("sys", List.of(), List.of(user("read it", pdf)))));
        assertEquals(FailureReason.INVALID_REQUEST, failed.reason());
        assertFalse(failed.retryable());
        assertTrue(failed.detail().contains(notes.id()) && failed.detail().contains("s1"), failed.detail());
        assertEquals(before, server.requests.size());
    }

    // ---- 按会话的选择发（model-picker 任务 8）：claude-model-requests.txt，只加新用例，上面的期望文件不变 ----

    private static final Path MODEL_EXPECTED = Path.of("src/test/resources/model/claude-model-requests.txt");
    private static final ModelChoice OPUS_HIGH = new ModelChoice("claude", "claude-opus-5-5", "high");

    private static ReasoningBlock thinking(String text, String signature, String model) {
        Map<String, Json> raw = new LinkedHashMap<>();
        raw.put("type", new Json.Str("thinking"));
        raw.put("thinking", new Json.Str(text));
        raw.put("signature", new Json.Str(signature));
        return new ReasoningBlock("claude", new Json.Obj(raw), model == null ? null : "claude", model);
    }

    /** Sonnet 写的一轮、Opus 写的一轮、本地 qwen 写的一轮，然后换到 Opus 再问。 */
    private static Prompt switchedHistory() {
        return new Prompt("sys", List.of(), List.of(
                Message.user("q1"),
                new Message(Role.ASSISTANT, List.of(thinking("sonnet thought", "SIG-S", "claude-sonnet-5-5"), new TextBlock("a1")), false),
                Message.user("q2"),
                new Message(Role.ASSISTANT, List.of(thinking("old thought", "SIG-OLD", null), new TextBlock("a2")), false),
                Message.user("q3"),
                new Message(Role.ASSISTANT, List.of(new ReasoningBlock("openai", new Json.Str("qwen thought"), "local", "qwen3.8"),
                        new TextBlock("a3")), false),
                Message.user("q4"),
                new Message(Role.SYSTEM, List.of(new ModelChangeBlock("local/qwen3.8", "claude/claude-opus-5-5")), false)),
                Prompt.Output.DEFAULT, OPUS_HIGH);
    }

    private static List<Sample> modelSamples() {
        ClaudeClient.Settings defaults = settings(8192, "medium", true, false);
        return List.of(
                new Sample("the choice picks the model and effort", defaults,
                        new Prompt("sys", List.of(), List.of(Message.user("hi")), Prompt.Output.DEFAULT, OPUS_HIGH)),
                new Sample("every Claude model's thinking goes back, other providers' does not; the notice opens the question", defaults,
                        switchedHistory()),
                new Sample("the notice comes before a tool change on a model that supports it", model("claude-sonnet-5-5", false),
                        new Prompt(switched().system(), switched().tools(), List.of(Message.user("q1"),
                                new Message(Role.ASSISTANT, List.of(new TextBlock("a1")), false), Message.user("q2"),
                                new Message(Role.SYSTEM, List.of(new ModelChangeBlock("claude/claude-opus-5-5", "claude/claude-sonnet-5-5")), false),
                                switched().messages().getLast()), Prompt.Output.DEFAULT, new ModelChoice("claude", "claude-sonnet-5-5", "low"))));
    }

    /** 需求 6.1、6.3：期望文件。 */
    @Test
    void modelChoiceRequestBodies() throws IOException {
        StringBuilder actual = new StringBuilder();
        for (Sample sample : modelSamples()) {
            actual.append(sent(sample));
        }
        if (Boolean.getBoolean("claude.record")) {
            Files.writeString(MODEL_EXPECTED, actual, StandardCharsets.UTF_8);
        }
        assertEquals(Files.readString(MODEL_EXPECTED, StandardCharsets.UTF_8), actual.toString(), "request changed; see the class comment");
    }

    @Test
    void theChoiceDecidesModelEffortAndToolChanges() {
        ClaudeRequest request = new ClaudeRequest(settings(8192, "medium", true, false));
        RequestOptions chosen = request.options(new Prompt("sys", List.of(), List.of(Message.user("hi")), Prompt.Output.DEFAULT, OPUS_HIGH));
        assertEquals("claude-opus-5-5", chosen.model());
        assertTrue(JsonText.toText(chosen.extra()).contains("\"effort\":\"high\""));
        RequestOptions plain = request.options(new Prompt("sys", List.of(), List.of(Message.user("hi"))));
        assertEquals("claude-sonnet-5-5", plain.model(), "no choice: the route's default");
        assertTrue(JsonText.toText(plain.extra()).contains("\"effort\":\"medium\""));
        // 会话中途工具变化按选的模型判断，不按默认模型
        Prompt onSonnet5 = new Prompt(switched().system(), switched().tools(), switched().messages(), Prompt.Output.DEFAULT,
                new ModelChoice("claude", "claude-sonnet-5", "medium"));
        assertFalse(new ClaudeRequest(model("claude-sonnet-5-5", true)).options(onSonnet5).extra().toString()
                .contains(ClaudeRequest.TOOL_CHANGE_BETA));
    }

    /** 需求 6.1、M9：回复里的思考块记下路线和实际回复的模型；备用模型接手时是备用模型。 */
    @Test
    void thinkingRecordsTheRouteAndTheModelThatReplied() {
        server.body = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"model\":\"claude-opus-5\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
                + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"SIG\"}}\n\n"
                + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
                + "\"usage\":{\"output_tokens\":1}}\n\n"
                + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        ModelOutcome.Replied chosen = assertInstanceOf(ModelOutcome.Replied.class, client(settings(8192, "medium", true, false))
                .call(new Prompt("sys", List.of(), List.of(Message.user("hi")), Prompt.Output.DEFAULT, OPUS_HIGH), IGNORE));
        ReasoningBlock block = assertInstanceOf(ReasoningBlock.class, chosen.content().getFirst());
        assertEquals("claude", block.route());
        assertEquals("claude-opus-5", block.model(), "the fallback model wrote it");

        ModelOutcome.Replied old = assertInstanceOf(ModelOutcome.Replied.class, client(settings(8192, "medium", true, false))
                .call(new Prompt("sys", List.of(), List.of(Message.user("hi"))), IGNORE));
        ReasoningBlock unnamed = assertInstanceOf(ReasoningBlock.class, old.content().getFirst());
        assertEquals(null, unnamed.route(), "no choice, nothing recorded: the journal line stays as before");
    }
}
