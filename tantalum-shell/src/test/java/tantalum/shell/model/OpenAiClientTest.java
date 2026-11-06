package tantalum.shell.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tantalum.value.attachment.AttachmentText;
import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.model.ToolSpec;

class OpenAiClientTest {

    private static final Prompt HI = new Prompt("sys", List.of(), List.of(Message.user("hi")));

    private FakeServer server;
    private final List<String> chunks = new ArrayList<>();
    private final ChunkListener listener = new ChunkListener() {
        @Override public void onText(String text) { chunks.add(text); }
        @Override public void onReasoning(String text) { chunks.add("r:" + text); }
    };

    @BeforeEach
    void start() throws Exception {
        server = new FakeServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private OpenAiClient client(Duration first, Duration between) {
        return new OpenAiClient(server.url(), "qwen", null, 4096, first, between, OverflowRules.defaults());
    }

    private ModelOutcome call(Prompt prompt) throws Exception {
        return client(Duration.ofSeconds(5), Duration.ofSeconds(5)).call(prompt, listener);
    }

    private static String data(String json) {
        return "data: " + json + "\n\n";
    }

    private static String delta(String delta) {
        return data("{\"model\":\"qwen-real\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":null}]}");
    }

    private static String finish(String reason) {
        return data("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + reason
                + "\"}],\"usage\":{\"prompt_tokens\":54,\"completion_tokens\":20,\"prompt_tokens_details\":{\"cached_tokens\":49}}}")
                + "data: [DONE]\n\n";
    }

    /** 上游接受连接却不回响应头：看门狗关不了还没拿到的响应体，必须靠请求超时结束。 */
    @Test
    void missingResponseHeadersTimeOutWithinFirstChunk() throws Exception {
        server.hangBeforeHeaders = true;
        long started = System.nanoTime();
        ModelOutcome.Failed failed = assertInstanceOf(ModelOutcome.Failed.class,
                client(Duration.ofMillis(300), Duration.ofSeconds(5)).call(HI, listener));
        assertEquals(FailureReason.TIMEOUT, failed.reason());
        assertTrue(failed.retryable());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
    }

    @Test
    void malformedChunkIsARetryableConnectionErrorKeepingTheText() throws Exception {
        server.body = delta("{\"content\":\"Hel\"}") + "data: {not json\n\n" + finish("stop");
        ModelOutcome.Failed failed = assertInstanceOf(ModelOutcome.Failed.class, call(HI));
        assertEquals(FailureReason.CONNECTION_ERROR, failed.reason());
        assertTrue(failed.retryable());
        assertEquals("Hel", failed.partialText());
    }

    @Test
    void hugeErrorBodyIsTruncatedInTheDetail() throws Exception {
        server.status = 500;
        server.contentType = "application/json";
        server.body = "x".repeat(100_000);
        ModelOutcome.Failed failed = assertInstanceOf(ModelOutcome.Failed.class, call(HI));
        assertTrue(failed.detail().length() < 5000, "detail length " + failed.detail().length());
        assertTrue(failed.detail().endsWith("..."));
    }

    @Test
    void streamsTextSkipsKeepAliveAndReadsUsage() throws Exception {
        server.body = ": keep-alive\n\n" + delta("{\"content\":\"Hel\"}") + ": keep-alive\n\n"
                + delta("{\"content\":\"lo\"}") + finish("stop");

        ModelOutcome.Replied reply = assertInstanceOf(ModelOutcome.Replied.class, call(HI));

        assertEquals(List.of(new TextBlock("Hello")), reply.content());
        assertEquals(StopReason.END_TURN, reply.stop());
        assertEquals("qwen-real", reply.actualModel());
        assertEquals(new TokenUsage(5, 20, 49, 0), reply.usage());
        assertEquals(54, reply.usage().promptTotal());
        assertEquals(List.of("Hel", "lo"), chunks);
    }

    @Test
    void nullContentAndReasoningAreParsed() throws Exception {
        server.body = delta("{\"content\":null,\"reasoning_content\":\"think\"}") + delta("{\"content\":\"ok\"}") + finish("stop");
        ModelOutcome.Replied reply = assertInstanceOf(ModelOutcome.Replied.class, call(HI));
        assertEquals(new ReasoningBlock("openai", new Json.Str("think")), reply.content().get(0));
        assertEquals(new TextBlock("ok"), reply.content().get(1));
        assertEquals(List.of("r:think", "ok"), chunks);
    }

    @Test
    void toolCallFragmentsAreJoined() throws Exception {
        server.body = delta("{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"grep\",\"arguments\":\"{\\\"pat\"}}]}")
                + delta("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"tern\\\":\\\"x\\\"}\"}}]}")
                + delta("{\"tool_calls\":[{\"index\":1,\"id\":\"c2\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]}")
                + finish("tool_calls");

        ModelOutcome.Replied reply = assertInstanceOf(ModelOutcome.Replied.class, call(HI));

        ToolCallBlock first = (ToolCallBlock) reply.content().get(0);
        assertEquals("c1", first.id());
        assertEquals("{\"pattern\":\"x\"}", first.rawArguments());
        assertEquals(new Json.Str("x"), ((Json.Obj) first.arguments()).fields().get("pattern"));
        assertEquals("read", ((ToolCallBlock) reply.content().get(1)).name());
        assertEquals(StopReason.TOOL_USE, reply.stop());
    }

    /** 每个 tool_calls 分片报一次工具输入，没有参数的分片报空串；只用来量首 token。 */
    @Test
    void toolCallFragmentsAreReportedAsToolInput() throws Exception {
        server.body = delta("{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"grep\"}}]}")
                + delta("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"pat\"}}]}")
                + delta("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"tern\\\":1}\"}}]}")
                + finish("tool_calls");
        List<String> seen = new ArrayList<>();
        ChunkListener recording = new ChunkListener() {
            @Override public void onText(String text) { seen.add("text:" + text); }
            @Override public void onReasoning(String text) { seen.add("reasoning:" + text); }
            @Override public void onToolInput(String fragment) { seen.add("tool:" + fragment); }
        };

        ModelOutcome.Replied reply = assertInstanceOf(ModelOutcome.Replied.class,
                client(Duration.ofSeconds(5), Duration.ofSeconds(5)).call(HI, recording));

        assertEquals(List.of("tool:", "tool:{\"pat", "tool:tern\":1}"), seen);
        assertEquals("{\"pattern\":1}", ((ToolCallBlock) reply.content().getFirst()).rawArguments());
    }

    /** 选项和请求体一致；不记地址（可能带凭据），也不含密钥，而密钥确实在请求头里发出去了。 */
    @Test
    void optionsAreWhatIsSentWithoutAddressOrKey() throws Exception {
        server.body = finish("stop");
        String key = "sk-fake-" + "Hq3vN9";
        OpenAiClient client = new OpenAiClient(server.url() + "/", "qwen", key, 4096, Duration.ofSeconds(5), Duration.ofSeconds(5),
                OverflowRules.defaults());
        Json schema = new Json.Obj(Map.of("type", new Json.Str("object")));
        Prompt summary = new Prompt("sys", List.of(new ToolSpec("grep", "g", schema)), List.of(Message.user("q")), new Prompt.Output(false, 512));

        for (Prompt prompt : List.of(HI, summary)) {
            tantalum.value.model.RequestOptions options = client.options(prompt);
            client.call(prompt, listener);
            tools.jackson.databind.JsonNode body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(server.requests.getLast());
            assertEquals("openai", options.provider());
            assertEquals(body.path("model").asString(), options.model());
            assertEquals(body.path("max_tokens").asLong(), options.maxTokens());
            assertEquals(prompt.output().toolsAllowed(), options.toolsAllowed());
            assertEquals("{\"stream\":true,\"stream_options\":{\"include_usage\":true}}", tantalum.shell.json.JsonText.toText(options.extra()));
            assertTrue(body.path("stream").asBoolean());
            assertTrue(body.path("stream_options").path("include_usage").asBoolean());
            assertEquals(List.of("Bearer " + key), server.requestHeaders.getLast().get("authorization"), "the fake key must really be sent");
            String recorded = tantalum.shell.journal.EventCodec.withBuiltins().mapper().writeValueAsString(options);
            assertFalse(recorded.contains("Hq3vN9"), "key leaked");
            assertFalse(recorded.contains("127.0.0.1"), "address leaked");
            assertFalse(recorded.contains(server.url()), "address leaked");
        }
        assertEquals(512, client.options(summary).maxTokens());
        assertFalse(client.options(summary).toolsAllowed());
        assertEquals(4096, client.options(HI).maxTokens());
    }

    @Test
    void invalidToolArgumentsAreMarkedNotThrown() throws Exception {
        server.body = delta("{\"tool_calls\":[{\"index\":0,\"id\":\"c\",\"function\":{\"name\":\"t\",\"arguments\":\"{oops\"}}]}") + finish("length");
        ModelOutcome.Replied reply = assertInstanceOf(ModelOutcome.Replied.class, call(HI));
        ToolCallBlock call = (ToolCallBlock) reply.content().getFirst();
        assertFalse(call.argumentsValid());
        assertNull(call.arguments());
        assertEquals(StopReason.MAX_TOKENS, reply.stop());
    }

    @Test
    void requestCarriesTheWholePromptInOrder() throws Exception {
        server.body = finish("stop");
        Json schema = new Json.Obj(Map.of("type", new Json.Str("object")));
        List<ContentBlock> assistant = List.of(
                new ReasoningBlock("openai", new Json.Str("mine")),
                new ReasoningBlock("claude", new Json.Str("not mine")),
                new TextBlock("calling"),
                new ToolCallBlock("c1", "grep", "{\"a\":1}", new Json.Obj(Map.of())));
        Prompt prompt = new Prompt("sys", List.of(new ToolSpec("grep", "search", schema)), List.of(
                Message.user("q"),
                new Message(Role.ASSISTANT, assistant, false),
                new Message(Role.USER, List.of(new ToolResultBlock("c1", "out", true, null)), false)));

        call(prompt);

        String body = server.requests.getFirst();
        assertTrue(body.contains("\"stream\":true"), body);
        assertTrue(body.contains("\"role\":\"system\",\"content\":\"sys\""), body);
        assertTrue(body.contains("\"reasoning_content\":\"mine\""), body);
        assertFalse(body.contains("not mine"), body);
        assertTrue(body.contains("\"tool_call_id\":\"c1\""), body);
        assertTrue(body.contains("\"arguments\":\"{\\\"a\\\":1}\""), body);
        assertTrue(body.contains("\"name\":\"grep\""), body);
        assertTrue(body.contains("\"max_tokens\":4096"), body);
    }

    @Test
    void summaryOutputDisablesToolsAndOverridesMaxTokens() throws Exception {
        server.body = finish("stop");
        Json schema = new Json.Obj(Map.of("type", new Json.Str("object")));
        call(new Prompt("sys", List.of(new ToolSpec("grep", "search", schema)), List.of(Message.user("summarize")),
                new Prompt.Output(false, 8192)));

        String body = server.requests.getFirst();
        assertTrue(body.contains("\"tool_choice\":\"none\""), body);
        assertTrue(body.contains("\"name\":\"grep\""), body);
        assertTrue(body.contains("\"max_tokens\":8192"), body);
    }

    @Test
    void defaultOutputSendsTheSameRequestAsBefore() throws Exception {
        server.body = finish("stop");
        call(new Prompt("sys", List.of(), List.of(Message.user("hi"))));
        call(new Prompt("sys", List.of(), List.of(Message.user("hi")), Prompt.Output.DEFAULT));
        assertEquals(server.requests.get(0), server.requests.get(1));
        assertFalse(server.requests.getFirst().contains("tool_choice"), server.requests.getFirst());
    }

    @Test
    void contextOverflowIsNotRetryable() throws Exception {
        server.status = 400;
        server.contentType = "application/json";
        server.body = "{\"error\":{\"message\":\"request exceeds the context window: prompt (560052 tokens)\"}}";
        ModelOutcome.Failed failed = assertInstanceOf(ModelOutcome.Failed.class, call(HI));
        assertEquals(FailureReason.CONTEXT_OVERFLOW, failed.reason());
        assertFalse(failed.retryable());
    }

    @Test
    void rateLimitIsRetryableWithRetryAfter() throws Exception {
        server.status = 429;
        server.headers = Map.of("retry-after", "30");
        server.body = "slow down";
        ModelOutcome.Failed failed = assertInstanceOf(ModelOutcome.Failed.class, call(HI));
        assertEquals(FailureReason.RATE_LIMITED, failed.reason());
        assertTrue(failed.retryable());
        assertEquals(Duration.ofSeconds(30), failed.retryAfter());
    }

    @Test
    void serverErrorIsRetryableAndBadRequestIsNot() throws Exception {
        server.status = 503;
        assertTrue(assertInstanceOf(ModelOutcome.Failed.class, call(HI)).retryable());
        server.status = 400;
        ModelOutcome.Failed bad = assertInstanceOf(ModelOutcome.Failed.class, call(HI));
        assertFalse(bad.retryable());
        assertEquals(FailureReason.INVALID_REQUEST, bad.reason());
    }

    @Test
    void connectionFailureIsRetryable() throws Exception {
        String url = server.url();
        server.close();
        ModelOutcome outcome = new OpenAiClient(url, "m", null, 16, Duration.ofSeconds(5), Duration.ofSeconds(5),
                OverflowRules.defaults()).call(HI, listener);
        ModelOutcome.Failed failed = assertInstanceOf(ModelOutcome.Failed.class, outcome);
        assertEquals(FailureReason.CONNECTION_ERROR, failed.reason());
        assertTrue(failed.retryable());
    }

    @Test
    void streamEndingWithoutFinishIsAFailureWithPartialText() throws Exception {
        server.body = delta("{\"content\":\"part\"}");
        ModelOutcome.Failed failed = assertInstanceOf(ModelOutcome.Failed.class, call(HI));
        assertTrue(failed.retryable());
        assertEquals("part", failed.partialText());
    }

    @Test
    void silenceTimesOutWithPartialText() throws Exception {
        server.body = delta("{\"content\":\"part\"}");
        server.hang = true;
        server.keepAlive = false;
        ModelOutcome.Failed failed = assertInstanceOf(ModelOutcome.Failed.class,
                client(Duration.ofSeconds(5), Duration.ofMillis(100)).call(HI, listener));
        assertEquals(FailureReason.TIMEOUT, failed.reason());
        assertEquals("part", failed.partialText());
        assertTrue(failed.retryable());
    }

    @Test
    void keepAliveCommentsCountAsActivity() throws Exception {
        server.body = delta("{\"content\":\"part\"}");
        server.hang = true;
        OpenAiClient client = client(Duration.ofSeconds(5), Duration.ofMillis(200));
        ModelOutcome[] outcome = new ModelOutcome[1];
        Thread caller = Thread.ofVirtual().start(() -> outcome[0] = client.call(HI, listener));
        caller.join(600);
        assertTrue(caller.isAlive(), "keep-alive must prevent the timeout");
        caller.interrupt();
        caller.join(5_000);
        assertInstanceOf(ModelOutcome.Interrupted.class, outcome[0]);
    }

    @Test
    void interruptReturnsWhatWasReceivedAndDisconnects() throws Exception {
        server.body = delta("{\"content\":\"seen\"}");
        server.hang = true;
        OpenAiClient client = client(Duration.ofSeconds(30), Duration.ofSeconds(30));
        ModelOutcome[] outcome = new ModelOutcome[1];
        Thread caller = Thread.ofVirtual().start(() -> outcome[0] = client.call(HI, listener));
        for (int i = 0; i < 500 && chunks.isEmpty(); i++) {
            Thread.sleep(5);
        }
        caller.interrupt();
        caller.join(5_000);
        assertEquals("seen", assertInstanceOf(ModelOutcome.Interrupted.class, outcome[0]).text());
        assertTrue(server.disconnected.await(5, TimeUnit.SECONDS));
    }

    // ---- 附件（attachments 任务 16）：openai-attachment-requests.txt，收图片和不收图片各一组 ----

    private static final java.nio.file.Path ATTACHMENT_EXPECTED = java.nio.file.Path.of("src/test/resources/model/openai-attachment-requests.txt");
    private static final ImageRef PNG = new ImageRef("sha256:" + "a".repeat(64), "cat.png", "image/png", 3, 4, 3);
    private static final FileRef PDF = new FileRef("sha256:" + "d".repeat(64), "report.pdf", "application/pdf", 9, 3);

    /** 图片字节固定是 {@code IMG}；OpenAI 兼容路线不读文件内容。 */
    private static final AttachmentMedia MEDIA = new AttachmentMedia() {
        @Override
        public Image image(ImageRef ref) {
            return new Image("IMG".getBytes(java.nio.charset.StandardCharsets.US_ASCII), ref.mediaType());
        }

        @Override
        public byte[] file(FileRef ref) {
            throw new AssertionError("files are never read on this route: " + ref.name());
        }
    };

    private static List<Prompt> attachmentPrompts() {
        List<ContentBlock> call = List.of(new ToolCallBlock("t1", "read_file", "{}", new Json.Obj(Map.of())));
        return List.of(
                new Prompt("sys", List.of(), List.of(Message.user("what is this?", List.of(PNG, PDF)))),
                new Prompt("sys", List.of(), List.of(Message.user("", List.of(PNG)))),
                new Prompt("sys", List.of(), List.of(Message.user("look"), new Message(Role.ASSISTANT, call, false),
                        new Message(Role.USER, List.of(new ToolResultBlock("t1", AttachmentText.image(PNG), false, null, List.of(PNG)),
                                new TextBlock("steer")), false))));
    }

    @Test
    void attachmentRequestBodiesWithAndWithoutVision() throws Exception {
        StringBuilder actual = new StringBuilder();
        for (boolean vision : new boolean[] {true, false}) {
            OpenAiClient client = new OpenAiClient(server.url(), "qwen", null, 4096, Duration.ofSeconds(5), Duration.ofSeconds(5),
                    OverflowRules.defaults(), vision);
            List<Prompt> prompts = attachmentPrompts();
            for (int i = 0; i < prompts.size(); i++) {
                server.body = finish("stop");
                client.call(prompts.get(i), MEDIA, listener);
                actual.append("== vision ").append(vision).append(", prompt ").append(i + 1).append('\n')
                        .append(server.requests.getLast()).append('\n');
            }
        }
        if (Boolean.getBoolean("openai.record")) {
            java.nio.file.Files.writeString(ATTACHMENT_EXPECTED, actual);
        }
        assertEquals(java.nio.file.Files.readString(ATTACHMENT_EXPECTED), actual.toString(), "request changed; rerun with -Dopenai.record=true");
    }

    /** web-tools 需求 3.3：模型不收图片时，工具结果（截图、MCP 图片）里的图片只剩 textOnly 那一行，请求里没有图片数据。 */
    @Test
    void toolResultImagesBecomeOneLineWhenTheModelTakesTextOnly() throws Exception {
        OpenAiClient client = new OpenAiClient(server.url(), "qwen", null, 4096, Duration.ofSeconds(5), Duration.ofSeconds(5),
                OverflowRules.defaults(), false);
        server.body = finish("stop");
        client.call(attachmentPrompts().get(2), MEDIA, listener);
        String body = server.requests.getLast();
        assertTrue(body.contains(AttachmentText.textOnly(PNG).replace("\"", "\\\"")), body);
        assertFalse(body.contains("image_url"), body);
        assertFalse(body.contains(java.util.Base64.getEncoder().encodeToString("IMG".getBytes(java.nio.charset.StandardCharsets.US_ASCII))), body);
    }

    // ---- 会话中途换工具（core-tools 任务 20）：工具只发现在开着的，变化写成一段说明 ----

    private static final java.nio.file.Path TOOL_CHANGE_EXPECTED = java.nio.file.Path.of("src/test/resources/model/openai-tool-change-requests.txt");

    @Test
    void toolChangeRequestBodies() throws Exception {
        Json schema = new Json.Obj(Map.of("type", new Json.Str("object")));
        List<ToolSpec> tools = List.of(new ToolSpec("grep", "search", schema), new ToolSpec("read_file", "read", schema),
                new ToolSpec("write_file", "write", schema, true));
        Prompt before = new Prompt("sys", tools, List.of(Message.user("q1")));
        Prompt after = new Prompt("sys", tools, List.of(Message.user("q1"), new Message(Role.ASSISTANT, List.of(new TextBlock("a1")), false),
                Message.user("q2"), new Message(Role.SYSTEM, List.of(new ToolChangeBlock(List.of("write_file"), List.of("grep"))), false)));
        OpenAiClient client = new OpenAiClient(server.url(), "qwen", null, 4096, Duration.ofSeconds(5), Duration.ofSeconds(5),
                OverflowRules.defaults(), false);
        StringBuilder actual = new StringBuilder();
        for (Prompt prompt : List.of(before, after)) {
            server.body = finish("stop");
            client.call(prompt, AttachmentMedia.NONE, listener);
            actual.append("== ").append(prompt == before ? "before the change" : "after the change").append('\n')
                    .append(server.requests.getLast()).append('\n');
        }
        if (Boolean.getBoolean("openai.record")) {
            java.nio.file.Files.writeString(TOOL_CHANGE_EXPECTED, actual);
        }
        assertEquals(java.nio.file.Files.readString(TOOL_CHANGE_EXPECTED), actual.toString(), "request changed; rerun with -Dopenai.record=true");
    }
}
