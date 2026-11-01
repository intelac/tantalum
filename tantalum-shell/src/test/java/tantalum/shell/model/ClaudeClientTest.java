package tantalum.shell.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import tantalum.value.model.ToolResultBlock;
import tantalum.value.model.ToolSpec;

class ClaudeClientTest {

    private static final Prompt HI = new Prompt("sys", List.of(), List.of(Message.user("hi")));
    private static final String START = event("message_start", """
            {"type":"message_start","message":{"id":"m","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":373,"output_tokens":1,"cache_read_input_tokens":7,"cache_creation_input_tokens":9}}}""");
    private static final String STOP = event("message_stop", "{\"type\":\"message_stop\"}");

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

    private static String event(String name, String json) {
        return "event: " + name + "\ndata: " + json.replace("\n", "") + "\n\n";
    }

    private static String blockStart(int index, String block) {
        return event("content_block_start", "{\"type\":\"content_block_start\",\"index\":" + index + ",\"content_block\":" + block + "}");
    }

    private static String blockDelta(int index, String delta) {
        return event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":" + index + ",\"delta\":" + delta + "}");
    }

    private static String blockStop(int index) {
        return event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":" + index + "}");
    }

    private static String messageDelta(String reason, String details) {
        return event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + reason
                + "\",\"stop_sequence\":null,\"stop_details\":" + details + "},\"usage\":{\"output_tokens\":50}}");
    }

    private ClaudeClient client(Duration first, Duration between) {
        AnthropicClient sdk = AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(server.url()).maxRetries(0).build();
        return new ClaudeClient(sdk, "claude-sonnet-5-5", 64000, "medium", true, first, between, OverflowRules.defaults());
    }

    private ModelOutcome call(Prompt prompt) throws Exception {
        return client(Duration.ofSeconds(5), Duration.ofSeconds(5)).call(prompt, listener);
    }

    @Test
    void streamsTextAndReadsUsage() throws Exception {
        server.body = START + blockStart(0, "{\"type\":\"text\",\"text\":\"\"}")
                + blockDelta(0, "{\"type\":\"text_delta\",\"text\":\"Hel\"}")
                + blockDelta(0, "{\"type\":\"text_delta\",\"text\":\"lo\"}") + blockStop(0)
                + messageDelta("end_turn", "null") + STOP;

        ModelOutcome.Replied reply = assertInstanceOf(ModelOutcome.Replied.class, call(HI));

        assertEquals(List.of(new TextBlock("Hello")), reply.content());
        assertEquals(StopReason.END_TURN, reply.stop());
        assertEquals("claude-sonnet-5-5", reply.actualModel());
        assertEquals(new TokenUsage(373, 50, 7, 9), reply.usage());
        assertEquals(List.of("Hel", "lo"), chunks);
    }

    @Test
    void thinkingKeepsItsSignatureExactly() throws Exception {
        server.body = START + blockStart(0, "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}")
                + blockDelta(0, "{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}")
                + blockDelta(0, "{\"type\":\"signature_delta\",\"signature\":\"SIG123\"}") + blockStop(0)
                + blockStart(1, "{\"type\":\"redacted_thinking\",\"data\":\"ENC\"}") + blockStop(1)
                + messageDelta("end_turn", "null") + STOP;

        ModelOutcome.Replied reply = assertInstanceOf(ModelOutcome.Replied.class, call(HI));

        Json.Obj thinking = (Json.Obj) ((ReasoningBlock) reply.content().get(0)).raw();
        assertEquals("claude", ((ReasoningBlock) reply.content().get(0)).provider());
        assertEquals(new Json.Str("thinking"), thinking.fields().get("type"));
        assertEquals(new Json.Str("hmm"), thinking.fields().get("thinking"));
        assertEquals(new Json.Str("SIG123"), thinking.fields().get("signature"));
        Json.Obj redacted = (Json.Obj) ((ReasoningBlock) reply.content().get(1)).raw();
        assertEquals(new Json.Str("ENC"), redacted.fields().get("data"));
        assertEquals(List.of("r:hmm"), chunks);
    }

    @Test
    void toolInputFragmentsAreJoinedAndChecked() throws Exception {
        server.body = START + blockStart(0, "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"read_file\",\"input\":{}}")
                + blockDelta(0, "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\"}")
                + blockDelta(0, "{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"a.txt\\\"}\"}") + blockStop(0)
                + blockStart(1, "{\"type\":\"tool_use\",\"id\":\"t2\",\"name\":\"none\",\"input\":{}}") + blockStop(1)
                + blockStart(2, "{\"type\":\"tool_use\",\"id\":\"t3\",\"name\":\"cut\",\"input\":{}}")
                + blockDelta(2, "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"pa\"}") + blockStop(2)
                + messageDelta("tool_use", "null") + STOP;

        ModelOutcome.Replied reply = assertInstanceOf(ModelOutcome.Replied.class, call(HI));

        ToolCallBlock first = (ToolCallBlock) reply.content().get(0);
        assertEquals("t1", first.id());
        assertEquals(new Json.Str("a.txt"), ((Json.Obj) first.arguments()).fields().get("path"));
        assertEquals(new Json.Obj(Map.of()), ((ToolCallBlock) reply.content().get(1)).arguments());
        assertFalse(((ToolCallBlock) reply.content().get(2)).argumentsValid());
        assertNull(((ToolCallBlock) reply.content().get(2)).arguments());
        assertEquals(StopReason.TOOL_USE, reply.stop());
    }

    @Test
    void refusalIsAFailureThatKeepsItsCategory() throws Exception {
        server.body = START + messageDelta("refusal", "{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"no\"}") + STOP;
        ModelOutcome.Failed failed = assertInstanceOf(ModelOutcome.Failed.class, call(HI));
        assertEquals(FailureReason.REFUSAL, failed.reason());
        assertFalse(failed.retryable());
        assertTrue(failed.detail().contains("cyber"), failed.detail());
    }

    @Test
    void rateLimitCarriesRetryAfterAndIsTriedOnce() throws Exception {
        server.status = 429;
        server.contentType = "application/json";
        server.headers = Map.of("retry-after", "12");
        server.body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow\"}}";
        ModelOutcome.Failed failed = assertInstanceOf(ModelOutcome.Failed.class, call(HI));
        assertEquals(FailureReason.RATE_LIMITED, failed.reason());
        assertEquals(Duration.ofSeconds(12), failed.retryAfter());
        assertTrue(failed.retryable());
        assertEquals(1, server.hits.get());
    }

    @Test
    void overloadedIsRetryable() throws Exception {
        server.status = 529;
        server.contentType = "application/json";
        server.body = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}";
        ModelOutcome.Failed failed = assertInstanceOf(ModelOutcome.Failed.class, call(HI));
        assertEquals(FailureReason.SERVER_ERROR, failed.reason());
        assertTrue(failed.retryable());
    }

    @Test
    void contextOverflowIsNotRetryable() throws Exception {
        server.status = 400;
        server.contentType = "application/json";
        server.body = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"prompt is too long: 2 tokens > 1 maximum\"}}";
        ModelOutcome.Failed failed = assertInstanceOf(ModelOutcome.Failed.class, call(HI));
        assertEquals(FailureReason.CONTEXT_OVERFLOW, failed.reason());
        assertFalse(failed.retryable());
    }

    @Test
    void streamEndingEarlyIsRetryableWithPartialText() throws Exception {
        server.body = START + blockStart(0, "{\"type\":\"text\",\"text\":\"\"}") + blockDelta(0, "{\"type\":\"text_delta\",\"text\":\"pa\"}");
        ModelOutcome.Failed failed = assertInstanceOf(ModelOutcome.Failed.class, call(HI));
        assertTrue(failed.retryable());
        assertEquals("pa", failed.partialText());
    }

    @Test
    void silenceTimesOut() throws Exception {
        server.body = START;
        server.hang = true;
        server.keepAlive = false;
        ModelOutcome.Failed failed = assertInstanceOf(ModelOutcome.Failed.class,
                client(Duration.ofSeconds(5), Duration.ofMillis(100)).call(HI, listener));
        assertEquals(FailureReason.TIMEOUT, failed.reason());
    }

    @Test
    void interruptReturnsWhatWasReceived() throws Exception {
        server.body = START + blockStart(0, "{\"type\":\"text\",\"text\":\"\"}") + blockDelta(0, "{\"type\":\"text_delta\",\"text\":\"seen\"}");
        server.hang = true;
        ClaudeClient client = client(Duration.ofSeconds(30), Duration.ofSeconds(30));
        ModelOutcome[] outcome = new ModelOutcome[1];
        Thread caller = Thread.ofVirtual().start(() -> outcome[0] = client.call(HI, listener));
        for (int i = 0; i < 500 && chunks.isEmpty(); i++) {
            Thread.sleep(5);
        }
        caller.interrupt();
        caller.join(5_000);
        assertEquals("seen", assertInstanceOf(ModelOutcome.Interrupted.class, outcome[0]).text());
    }

    @Test
    void requestSendsThePromptAndOnlyOwnReasoningUnchanged() throws Exception {
        server.body = START + messageDelta("end_turn", "null") + STOP;
        Map<String, Json> thinking = new LinkedHashMap<>();
        thinking.put("type", new Json.Str("thinking"));
        thinking.put("thinking", new Json.Str("hmm"));
        thinking.put("signature", new Json.Str("SIG123"));
        Map<String, Json> redacted = new LinkedHashMap<>();
        redacted.put("type", new Json.Str("redacted_thinking"));
        redacted.put("data", new Json.Str("ENC"));
        Map<String, Json> schema = new LinkedHashMap<>();
        schema.put("type", new Json.Str("object"));
        schema.put("properties", new Json.Obj(Map.of()));
        List<ContentBlock> assistant = List.of(
                new ReasoningBlock("claude", new Json.Obj(thinking)),
                new ReasoningBlock("claude", new Json.Obj(redacted)),
                new ReasoningBlock("openai", new Json.Str("not mine")),
                new TextBlock("calling"),
                new ToolCallBlock("t1", "grep", "{}", new Json.Obj(Map.of("a", new Json.Str("b")))),
                new ToolCallBlock("t2", "bad", "{oops", null));
        Prompt prompt = new Prompt("sys", List.of(new ToolSpec("grep", "search", new Json.Obj(schema))), List.of(
                Message.user("q"),
                new Message(Role.ASSISTANT, assistant, false),
                new Message(Role.USER, List.of(new ToolResultBlock("t1", "out", true, null)), false)));

        call(prompt);

        String body = server.requests.getFirst();
        assertTrue(body.contains("\"model\":\"claude-sonnet-5-5\""), body);
        assertTrue(body.contains("\"stream\":true"), body);
        assertTrue(body.contains("\"text\":\"sys\""), body);
        assertTrue(body.contains("\"signature\":\"SIG123\""), body);
        assertTrue(body.contains("\"data\":\"ENC\""), body);
        assertFalse(body.contains("not mine"), body);
        assertTrue(body.contains("\"tool_use_id\":\"t1\""), body);
        assertTrue(body.contains("\"is_error\":true"), body);
        assertTrue(body.contains("\"eager_input_streaming\":true"), body);
        assertTrue(body.contains("\"effort\":\"medium\""), body);
        assertTrue(body.contains("\"fallbacks\":\"default\""), body);
        assertFalse(body.contains("temperature"), body);
    }

    /** 缓存断点只在三处：system 块、最后一个工具定义、最后一条消息的最后一个块；其余地方没有，否则会浪费断点配额。 */
    @Test
    void requestMarksCacheBreakpointsOnSystemLastToolAndLastMessage() throws Exception {
        server.body = START + messageDelta("end_turn", "null") + STOP;
        Json schema = new Json.Obj(Map.of("type", new Json.Str("object")));
        Prompt prompt = new Prompt("sys", List.of(new ToolSpec("grep", "g", schema), new ToolSpec("read", "r", schema)), List.of(
                Message.user("q"),
                new Message(Role.ASSISTANT, List.of(new TextBlock("calling"),
                        new ToolCallBlock("t1", "grep", "{}", new Json.Obj(Map.of()))), false),
                new Message(Role.USER, List.of(new ToolResultBlock("t1", "out", false, null), new TextBlock("steer")), false)));

        call(prompt);

        tools.jackson.databind.JsonNode body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(server.requests.getFirst());
        assertEquals("ephemeral", body.path("system").get(0).path("cache_control").path("type").asString());
        assertTrue(body.path("tools").get(0).path("cache_control").isMissingNode());
        assertEquals("ephemeral", body.path("tools").get(1).path("cache_control").path("type").asString());
        tools.jackson.databind.JsonNode last = body.path("messages").get(2).path("content");
        assertTrue(last.get(0).path("cache_control").isMissingNode());
        assertEquals("ephemeral", last.get(1).path("cache_control").path("type").asString());
        assertTrue(body.path("messages").get(0).path("content").get(0).path("cache_control").isMissingNode());
        assertEquals(3, countOf(server.requests.getFirst(), "\"cache_control\""));
    }

    /** 摘要模式：工具定义照发（历史里有 tool_use，且要命中缓存），但禁止调用，输出上限按 Prompt 来。 */
    @Test
    void summaryOutputDisablesToolsAndOverridesMaxTokens() throws Exception {
        server.body = START + messageDelta("end_turn", "null") + STOP;
        Json schema = new Json.Obj(Map.of("type", new Json.Str("object")));
        Prompt prompt = new Prompt("sys", List.of(new ToolSpec("grep", "g", schema)), List.of(Message.user("summarize")),
                new Prompt.Output(false, 8192));

        call(prompt);

        tools.jackson.databind.JsonNode body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(server.requests.getFirst());
        assertEquals("none", body.path("tool_choice").path("type").asString(), server.requests.getFirst());
        assertEquals("grep", body.path("tools").get(0).path("name").asString());
        assertEquals(8192, body.path("max_tokens").asInt());
    }

    @Test
    void defaultOutputSendsTheSameRequestAsBefore() throws Exception {
        server.body = START + messageDelta("end_turn", "null") + STOP;
        call(new Prompt("sys", List.of(), List.of(Message.user("hi"))));
        call(new Prompt("sys", List.of(), List.of(Message.user("hi")), Prompt.Output.DEFAULT));
        assertEquals(server.requests.get(0), server.requests.get(1));
        assertFalse(server.requests.getFirst().contains("tool_choice"), server.requests.getFirst());
        assertTrue(server.requests.getFirst().contains("\"max_tokens\":64000"), server.requests.getFirst());
    }

    @Test
    void blankSystemIsOmittedInsteadOfSentAsAnEmptyBlock() throws Exception {
        server.body = START + messageDelta("end_turn", "null") + STOP;
        call(new Prompt("", List.of(), List.of(Message.user("hi"))));
        tools.jackson.databind.JsonNode body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(server.requests.getFirst());
        assertTrue(body.path("system").isMissingNode(), server.requests.getFirst());
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
