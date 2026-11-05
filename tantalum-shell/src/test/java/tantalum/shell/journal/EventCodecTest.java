package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;
import tantalum.value.event.Cancel;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.TimerFired;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.event.WorkDone;
import tantalum.value.id.CommandId;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.AttachmentBlock;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.RequestOptions;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.OwnerRef;

class EventCodecTest {

    private static final CommandId CMD = new CommandId("1-0");
    private static final RequestId REQ = new RequestId("r");
    private static final SubmissionId SUB = new SubmissionId("s");

    private final EventCodec codec = EventCodec.withBuiltins();

    private void assertRoundTrip(Event event) {
        String json = codec.encode(event);
        assertEquals(event, codec.decode(event.type(), event.version(), json), json);
    }

    private static Json complexJson() {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("z", new Json.Str("last key first"));
        fields.put("nothing", new Json.Null());
        fields.put("flag", new Json.Bool(true));
        fields.put("exact", new Json.Num(new BigDecimal("1.10")));
        fields.put("list", new Json.Arr(List.of(new Json.Num(BigDecimal.ONE))));
        return new Json.Obj(fields);
    }

    @Test
    void everyBuiltinEventSurvivesARoundTrip() {
        List<ContentBlock> content = List.of(
                new TextBlock("hi"),
                new ReasoningBlock("claude", complexJson()),
                new ToolCallBlock("c", "grep", "{\"a\":1}", complexJson()),
                new ToolCallBlock("bad", "grep", "{oops", null),
                new ToolResultBlock("c", "out", true, "TOOL_OUTCOME_UNKNOWN"));
        List<Event> events = List.of(
                new UserPrompt(SUB, REQ, new OwnerRef.OfSession(new SessionId("s")), "hi"),
                new UserPrompt(SUB, REQ, new OwnerRef.OfCommand(CMD), "child"),
                new SteerSubmitted(SUB, REQ, "x"),
                new FollowUpSubmitted(SUB, REQ, "x"),
                new Cancel(REQ),
                new tantalum.value.event.RunInterrupted(),
                new ContextAssembled(CMD, "system", List.of(new ToolSpec("grep", "d", complexJson())), null, List.of()),
                new ContextAssembled(CMD, "system", List.of(), new CompactionSettings(200_000, 64_000, 20_000, 32_768, 4_096, 8_192),
                List.of(new tantalum.value.state.ContextSection("memory-index", "idx", true))),
                new tantalum.value.event.CompactRequested(SUB, REQ, "focus on the test results"),
                new tantalum.value.event.CompactRequested(SUB, REQ, null),
                new ModelReplied(CMD, content, StopReason.TOOL_USE, "m", new TokenUsage(1, 2, 3, 4), null, null, null),
                new ModelReplied(CMD, List.of(), StopReason.END_TURN, "m", null, null, null, null),
                new ModelFailed(CMD, FailureReason.RATE_LIMITED, true, Duration.ofSeconds(30), "partial", "429", null, null, null),
                new ModelFailed(CMD, FailureReason.REFUSAL, false, null, null, "cyber", null, null, null),
                new ModelInterrupted(CMD, "partial", null, null, null),
                new ToolFinished(CMD, "c", "out", false, null),
                new TimerFired(CMD),
                new WorkDone(CMD, "citation", complexJson(), false, "fix it", null),
                new WorkDone(CMD, "citation", new Json.Null(), true, null, null));
        events.forEach(this::assertRoundTrip);
    }

    // ---- 计时和请求选项：可空的展示字段，不升存储格式 ----

    private static final Instant STARTED = Instant.parse("2026-11-03T01:02:03.456Z");
    private static final Instant FIRST = Instant.parse("2026-11-03T01:02:04.001Z");
    private static final RequestOptions OPTIONS = new RequestOptions("claude", "claude-sonnet-5-5", 64000, false, complexJson());

    /** 改动前的程序写出的行（同样的事件，没有新字段），从 2026-11-03 的代码取样。 */
    private static final String OLD_REPLIED = "{\"command\":{\"value\":\"1-0\"},\"content\":[{\"kind\":\"text\",\"text\":\"hi\"},"
            + "{\"kind\":\"toolCall\",\"id\":\"c\",\"name\":\"grep\",\"rawArguments\":\"{\\\"a\\\":1}\",\"arguments\":"
            + "{\"z\":\"last key first\",\"nothing\":null,\"exact\":1.10}}],\"stop\":\"TOOL_USE\",\"actualModel\":\"m\","
            + "\"usage\":{\"input\":1,\"output\":2,\"cacheRead\":3,\"cacheWrite\":4}}";
    private static final String OLD_FAILED = "{\"command\":{\"value\":\"1-0\"},\"reason\":\"RATE_LIMITED\",\"retryable\":true,"
            + "\"retryAfter\":\"PT30S\",\"partialText\":\"partial\",\"detail\":\"429\"}";
    private static final String OLD_INTERRUPTED = "{\"command\":{\"value\":\"1-0\"},\"text\":\"partial\"}";
    private static final String OLD_TOOL = "{\"command\":{\"value\":\"1-0\"},\"toolCallId\":\"c\",\"text\":\"out\",\"error\":true}";
    private static final String OLD_WORK = "{\"command\":{\"value\":\"1-0\"},\"workType\":\"citation\",\"result\":"
            + "{\"z\":\"last key first\",\"nothing\":null,\"exact\":1.10},\"failed\":false,\"continueText\":\"fix it\"}";

    private static Json oldJson() {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("z", new Json.Str("last key first"));
        fields.put("nothing", new Json.Null());
        fields.put("exact", new Json.Num(new BigDecimal("1.10")));
        return new Json.Obj(fields);
    }

    /** 和取样时同样的五个事件，新字段为 null。 */
    private static List<Event> withoutNewFields() {
        return List.of(
                new ModelReplied(CMD, List.of(new TextBlock("hi"), new ToolCallBlock("c", "grep", "{\"a\":1}", oldJson())),
                        StopReason.TOOL_USE, "m", new TokenUsage(1, 2, 3, 4), null, null, null),
                new ModelFailed(CMD, FailureReason.RATE_LIMITED, true, Duration.ofSeconds(30), "partial", "429", null, null, null),
                new ModelInterrupted(CMD, "partial", null, null, null),
                new ToolFinished(CMD, "c", "out", true, null),
                new WorkDone(CMD, "citation", oldJson(), false, "fix it", null));
    }

    @Test
    void timingAndOptionsSurviveARoundTrip() {
        List<Event> events = List.of(
                new ModelReplied(CMD, List.of(new TextBlock("hi")), StopReason.END_TURN, "m", null, STARTED, FIRST, OPTIONS),
                new ModelReplied(CMD, List.of(), StopReason.END_TURN, "m", null, STARTED, null, null),
                new ModelFailed(CMD, FailureReason.SERVER_ERROR, true, null, "", "529", STARTED, FIRST, OPTIONS),
                new ModelInterrupted(CMD, "partial", STARTED, FIRST, OPTIONS),
                new ToolFinished(CMD, "c", "out", false, STARTED),
                new WorkDone(CMD, "citation", complexJson(), false, null, STARTED));
        events.forEach(this::assertRoundTrip);
        String line = codec.encode(events.getFirst());
        assertTrue(line.contains("\"startedAt\":\"2026-11-03T01:02:03.456Z\""), line);
        assertTrue(line.contains("\"firstTokenAt\":\"2026-11-03T01:02:04.001Z\""), line);
    }

    /** 字段为 null 时不写：脚本化模型和旧事件写出的行和改动前逐字节相同。 */
    @Test
    void nullFieldsWriteTheSameBytesAsBefore() {
        assertEquals(List.of(OLD_REPLIED, OLD_FAILED, OLD_INTERRUPTED, OLD_TOOL, OLD_WORK),
                withoutNewFields().stream().map(codec::encode).toList());
    }

    /** 旧行没有新字段，读成 null，所以存储格式不用升。 */
    @Test
    void oldLinesReadTheNewFieldsAsNull() {
        List<String> old = List.of(OLD_REPLIED, OLD_FAILED, OLD_INTERRUPTED, OLD_TOOL, OLD_WORK);
        List<Event> expected = withoutNewFields();
        for (int i = 0; i < old.size(); i++) {
            assertEquals(expected.get(i), codec.decode(expected.get(i).type(), 1, old.get(i)), old.get(i));
        }
    }

    // ---- 插件开关和中途换工具（core-tools 任务 15）----

    private static final String OLD_ASSEMBLED = "{\"command\":{\"value\":\"1-0\"},\"system\":\"sys\",\"tools\":[{\"name\":\"read_file\","
            + "\"description\":\"read\",\"schema\":{}}],\"sections\":[]}";

    @Test
    void enabledListsAndToolChangesSurviveARoundTrip() {
        ToolSpec read = new ToolSpec("read_file", "read", new Json.Obj(Map.of()));
        assertRoundTrip(new ContextAssembled(CMD, "sys", List.of(read), null, List.of(), List.of("read_file"), List.of("quota")));
        assertRoundTrip(new ContextAssembled(CMD, "sys", List.of(read), null, List.of(), List.of(), List.of()));
        assertRoundTrip(new ContextAssembled(CMD, "sys", List.of(read), null, List.of(), List.of("read_file"), List.of(),
                Map.of("read_file", "builtin", "browser_navigate", "mcp:browser")));
        assertRoundTrip(new ModelReplied(CMD, List.of(new ToolChangeBlock(List.of("write_file"), List.of("grep"))), StopReason.END_TURN,
                "m", null, null, null, null));
    }

    /** 不带名单时写出的行和加字段之前逐字节相同；旧行读出来名单是 null，表示全部开着。deferred 为 false 时不写出。 */
    @Test
    void contextWithoutListsWritesTheSameBytesAsBefore() {
        ContextAssembled old = new ContextAssembled(CMD, "sys", List.of(new ToolSpec("read_file", "read", new Json.Obj(Map.of()))), null,
                List.of());
        assertEquals(OLD_ASSEMBLED, codec.encode(old));
        ContextAssembled decoded = (ContextAssembled) codec.decode(ContextAssembled.TYPE, 2, OLD_ASSEMBLED);
        assertEquals(old, decoded);
        assertEquals(null, decoded.enabledTools());
        assertEquals(null, decoded.enabledPlugins());
    }

    /** compaction-threshold：旧设置不写出压缩点和系数，和加字段之前逐字节相同；旧行读出来是 0，按旧规则算。 */
    @Test
    void compactionSettingsWithoutAPointWriteTheSameBytesAsBefore() {
        CompactionSettings legacy = new CompactionSettings(200_000, 64_000, 20_000, 32_768, 4_096, 8_192);
        ContextAssembled old = new ContextAssembled(CMD, "sys", List.of(), legacy, List.of());
        String line = codec.encode(old);
        assertEquals("{\"command\":{\"value\":\"1-0\"},\"system\":\"sys\",\"tools\":[],\"compaction\":{\"window\":200000,"
                + "\"reserve\":64000,\"keepRecent\":20000,\"maxKeptResultBytes\":32768,\"maxSummarizedResultBytes\":4096,"
                + "\"summaryMaxTokens\":8192},\"sections\":[]}", line);
        CompactionSettings decoded = ((ContextAssembled) codec.decode(ContextAssembled.TYPE, 2, line)).compaction();
        assertEquals(legacy, decoded);
        assertEquals(136_000, decoded.threshold());
        assertEquals(3, decoded.bytesPerTokenOrLegacy());

        CompactionSettings chosen = new CompactionSettings(1_000_000, 64_000, 20_000, 32_768, 4_096, 8_192, 500_000, 1.5);
        assertRoundTrip(new ContextAssembled(CMD, "sys", List.of(), chosen, List.of()));
        assertTrue(codec.encode(new ContextAssembled(CMD, "sys", List.of(), chosen, List.of()))
                .contains("\"point\":500000,\"bytesPerToken\":1.5"));
    }

    /** 第 1 版的组装上下文（one-line-journal 第 1 步写的）照样能读，工具来源是空表。 */
    @Test
    void aFirstVersionContextReadsWithoutToolSources() {
        ContextAssembled decoded = (ContextAssembled) codec.decode(ContextAssembled.TYPE, 1, OLD_ASSEMBLED);
        assertEquals(Map.of(), decoded.toolSources());
        assertEquals("sys", decoded.system());
    }

    @Test
    void anEmptyEnabledListIsNotTheSameAsNoList() {
        String none = codec.encode(new ContextAssembled(CMD, "sys", List.of(), null, List.of(), List.of(), List.of()));
        assertTrue(none.contains("\"enabledTools\":[]"), none);
        assertEquals(List.of(), ((ContextAssembled) codec.decode(ContextAssembled.TYPE, 2, none)).enabledTools());
    }

    // ---- 附件（attachments 任务 8）----

    private static final String HASH = "0123456789abcdef".repeat(4);
    private static final ImageRef IMAGE = new ImageRef("sha256:" + HASH, "cat.png", "image/png", 1234, 2048, 1024, 4096, 2048);
    private static final FileRef PDF = new FileRef("sha256:" + "f".repeat(64), "report.pdf", "application/pdf", 99, 3);
    private static final FileRef TEXT = new FileRef("sha256:" + "e".repeat(64), "notes.txt", "text/plain", 5, null);

    @Test
    void attachmentsSurviveARoundTrip() {
        List<AttachmentRef> refs = List.of(IMAGE, PDF, TEXT);
        assertRoundTrip(new UserPrompt(SUB, REQ, new OwnerRef.OfSession(new SessionId("s")), "", refs));
        assertRoundTrip(new SteerSubmitted(SUB, REQ, "look", refs));
        assertRoundTrip(new FollowUpSubmitted(SUB, REQ, "later", refs));
        assertRoundTrip(new ToolFinished(CMD, "c", "Image ...", false, null, List.of(IMAGE)));
        assertRoundTrip(new ModelReplied(CMD, List.of(new TextBlock("x"), new AttachmentBlock(IMAGE), new AttachmentBlock(PDF),
                new ToolResultBlock("c", "out", false, null, List.of(IMAGE))), StopReason.END_TURN, "m", null, null, null, null));
    }

    /** {@code kind} 在前，没有值的字段不写：没缩小过的图片没有 source*，不是 PDF 的文件没有 pages。 */
    @Test
    void attachmentRefsHaveKindFirstAndOmitEmptyFields() {
        String line = codec.encode(new SteerSubmitted(SUB, REQ, "x", List.of(new ImageRef(IMAGE.id(), "a.png", "image/png", 1, 2, 3), TEXT)));
        assertTrue(line.contains("\"attachments\":[{\"kind\":\"image\",\"id\":\"sha256:" + HASH + "\",\"name\":\"a.png\","
                + "\"mediaType\":\"image/png\",\"bytes\":1,\"width\":2,\"height\":3},{\"kind\":\"file\","), line);
        assertTrue(line.endsWith("\"mediaType\":\"text/plain\",\"bytes\":5}]}"), line);
    }

    /** 没有附件时写出的行和加字段之前逐字节相同；旧行读出来是空列表。 */
    @Test
    void noAttachmentsWriteTheSameBytesAsBefore() {
        String prompt = "{\"submission\":{\"value\":\"s\"},\"request\":{\"value\":\"r\"},\"owner\":{\"kind\":\"session\","
                + "\"session\":{\"value\":\"s\"}},\"text\":\"hi\"}";
        String steer = "{\"submission\":{\"value\":\"s\"},\"request\":{\"value\":\"r\"},\"text\":\"x\"}";
        String replied = "{\"command\":{\"value\":\"1-0\"},\"content\":[{\"kind\":\"toolResult\",\"toolCallId\":\"c\","
                + "\"text\":\"out\",\"error\":false}],\"stop\":\"END_TURN\",\"actualModel\":\"m\"}";
        UserPrompt userPrompt = new UserPrompt(SUB, REQ, new OwnerRef.OfSession(new SessionId("s")), "hi");
        ModelReplied reply = new ModelReplied(CMD, List.of(new ToolResultBlock("c", "out", false, null)), StopReason.END_TURN, "m",
                null, null, null, null);
        assertEquals(prompt, codec.encode(userPrompt));
        assertEquals(steer, codec.encode(new SteerSubmitted(SUB, REQ, "x")));
        assertEquals(steer, codec.encode(new FollowUpSubmitted(SUB, REQ, "x")));
        assertEquals(OLD_TOOL, codec.encode(new ToolFinished(CMD, "c", "out", true, null)));
        assertEquals(replied, codec.encode(reply));

        assertEquals(List.of(), ((UserPrompt) codec.decode(UserPrompt.TYPE, 1, prompt)).attachments());
        assertEquals(List.of(), ((SteerSubmitted) codec.decode(SteerSubmitted.TYPE, 1, steer)).attachments());
        assertEquals(List.of(), ((FollowUpSubmitted) codec.decode(FollowUpSubmitted.TYPE, 1, steer)).attachments());
        assertEquals(List.of(), ((ToolFinished) codec.decode(ToolFinished.TYPE, 1, OLD_TOOL)).images());
        assertEquals(reply, codec.decode(ModelReplied.TYPE, 1, replied));
    }

    /** 旧程序读新行时忽略不认识的字段，不会把会话当成损坏。用一个现在也不认识的字段钉住这个默认行为。 */
    @Test
    void unknownFieldsAreIgnored() {
        String later = OLD_TOOL.substring(0, OLD_TOOL.length() - 1) + ",\"queuedFor\":{\"ms\":3}}";
        assertEquals(new ToolFinished(CMD, "c", "out", true, null), codec.decode(ToolFinished.TYPE, 1, later));
    }

    @Test
    void jsonKeepsKeyOrderAndNumberPrecision() {
        WorkDone event = new WorkDone(CMD, "w", complexJson(), false, null, null);
        WorkDone decoded = (WorkDone) codec.decode(event.type(), event.version(), codec.encode(event));
        Json.Obj object = (Json.Obj) decoded.result();
        assertEquals(List.of("z", "nothing", "flag", "exact", "list"), List.copyOf(object.fields().keySet()));
        assertEquals(new Json.Num(new BigDecimal("1.10")), object.fields().get("exact"));
    }

    @Test
    void invalidArgumentsStayNullWhileJsonNullStaysAValue() {
        ToolCallBlock bad = new ToolCallBlock("bad", "grep", "{oops", null);
        ToolCallBlock nullArgs = new ToolCallBlock("n", "grep", "null", new Json.Null());
        ModelReplied decoded = (ModelReplied) codec.decode(ModelReplied.TYPE, 1, codec.encode(
                new ModelReplied(CMD, List.of(bad, nullArgs), StopReason.TOOL_USE, "m", null, null, null, null)));
        assertNull(((ToolCallBlock) decoded.content().get(0)).arguments());
        assertEquals(new Json.Null(), ((ToolCallBlock) decoded.content().get(1)).arguments());
    }

    @Test
    void unknownTypeIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> codec.decode("Mystery", 1, "{}"));
        assertTrue(failure.getMessage().contains("Mystery"));
    }

    @Test
    void unknownVersionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(Cancel.TYPE, 2, "{}"));
    }

    @Test
    void duplicateRegistrationIsRejected() {
        assertThrows(IllegalStateException.class, () -> codec.register(Cancel.TYPE, 1, Cancel.class));
    }

    @Test
    void pluginEventCanBeRegistered() {
        record Approved(String by) implements Event {
            @Override public String type() { return "Approved"; }
            @Override public int version() { return 1; }
        }
        codec.register("Approved", 1, Approved.class);
        assertRoundTrip(new Approved("yi"));
    }

    @Test
    void corruptPayloadIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(Cancel.TYPE, 1, "{not json"));
    }
}
