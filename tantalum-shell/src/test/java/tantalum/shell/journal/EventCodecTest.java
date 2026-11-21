package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import tantalum.value.event.Delivery;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;
import tantalum.value.event.Cancel;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
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
import tantalum.value.attachment.ImageRefFixture;
import tantalum.value.event.ContextAssembledFixture;
import tantalum.value.event.ToolFinishedFixture;
import tantalum.value.event.UserPromptFixture;
import tantalum.value.state.CompactionSettingsFixture;

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
                UserPromptFixture.of(SUB, REQ, "hi"),
                new tantalum.value.event.SessionCreated(tantalum.value.state.SessionKind.HELPER, "/home/alice/app", true,
                        new tantalum.value.state.HelperOf(new SessionId("s"), "t3-0", "reviewer", "You review code.", List.of("read"))),
                new tantalum.value.event.Assignment("t3-0", new SessionId("s"), tantalum.value.event.Root.USER, "check module A", null),
                new tantalum.value.event.Report("t3-0", tantalum.value.assignment.AssignmentOutcome.COMPLETED, "fine", tantalum.value.event.Root.USER, new SessionId("s-t3")),
                new tantalum.value.event.Report("t3-1", tantalum.value.assignment.AssignmentOutcome.INTERRUPTED, null, tantalum.value.event.Root.OUTSIDE, new SessionId("s-t3")),
                new tantalum.value.event.StopRequested(REQ, null),
                new tantalum.value.event.AssignmentCancelled("t3-0", new SessionId("s")),
                new tantalum.value.event.TextWithdrawn(REQ, List.of(new tantalum.value.event.TextWithdrawn.Swap("my plate is 7Q2KD", "[withdrawn]")),
                        tantalum.value.event.TextWithdrawn.By.user()),
                new tantalum.value.event.TextWithdrawn(REQ, List.of(), new tantalum.value.event.TextWithdrawn.By(
                        tantalum.value.event.TextWithdrawn.Actor.AGENT, new SessionId("s"), tantalum.value.id.RunId.of(new SessionId("s"), 7), "c1")),
                UserPromptFixture.join(SUB, REQ, "x"),
                UserPromptFixture.of(SUB, REQ, "x"),
                new Cancel(REQ),
                new tantalum.value.event.Answered(SUB, REQ, "approval-c1", tantalum.value.question.Answer.of("once")),
                new tantalum.value.event.Answered(SUB, REQ, "ask-a1", new tantalum.value.question.Answer("keep", "归档到 old/")),
                new tantalum.value.event.RunInterrupted(),
                ContextAssembledFixture.of(CMD, "system", List.of(new ToolSpec("grep", "d", complexJson())), null, List.of()),
                ContextAssembledFixture.of(CMD, "system", List.of(), CompactionSettingsFixture.of(200_000, 64_000, 20_000, 32_768, 4_096, 8_192),
                List.of(new tantalum.value.state.ContextSection("memory-index", "idx", true))),
                new tantalum.value.event.CompactRequested(SUB, REQ, "focus on the test results"),
                new tantalum.value.event.CompactRequested(SUB, REQ, null),
                new ModelReplied(CMD, content, StopReason.TOOL_USE, "m", new TokenUsage(1, 2, 3, 4), null, null, null),
                new ModelReplied(CMD, List.of(), StopReason.END_TURN, "m", null, null, null, null),
                new ModelFailed(CMD, FailureReason.RATE_LIMITED, true, Duration.ofSeconds(30), "partial", "429", null, null, null),
                new ModelFailed(CMD, FailureReason.REFUSAL, false, null, null, "cyber", null, null, null),
                new ModelInterrupted(CMD, "partial", null, null, null),
                ToolFinishedFixture.of(CMD, "c", "out", false, null),
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
                ToolFinishedFixture.of(CMD, "c", "out", true, null),
                new WorkDone(CMD, "citation", oldJson(), false, "fix it", null));
    }

    @Test
    void timingAndOptionsSurviveARoundTrip() {
        List<Event> events = List.of(
                new ModelReplied(CMD, List.of(new TextBlock("hi")), StopReason.END_TURN, "m", null, STARTED, FIRST, OPTIONS),
                new ModelReplied(CMD, List.of(), StopReason.END_TURN, "m", null, STARTED, null, null),
                new ModelFailed(CMD, FailureReason.SERVER_ERROR, true, null, "", "529", STARTED, FIRST, OPTIONS),
                new ModelInterrupted(CMD, "partial", STARTED, FIRST, OPTIONS),
                ToolFinishedFixture.of(CMD, "c", "out", false, STARTED),
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

    /** qagent-align 需求 3.5、3.6：折算后的量只在有的时候写（上面两条守着没有时的字节）；写了能原样读回。 */
    @Test
    void aWeighedUsageIsWrittenWithItsAmountAndReadBack() {
        ModelReplied weighed = new ModelReplied(new CommandId("1-0"), List.of(new TextBlock("hi")), StopReason.END_TURN, "m",
                new TokenUsage(1, 2, 3, 4).weighed(1, 5, 1, 1), null, null, null);
        String line = codec.encode(weighed);
        assertTrue(line.endsWith("\"usage\":{\"input\":1,\"output\":2,\"cacheRead\":3,\"cacheWrite\":4,\"weighted\":18}}"), line);
        assertEquals(weighed, codec.decode(ModelReplied.TYPE, 1, line));
        // 全是 1 的系数：和没折算一个写法
        ModelReplied plain = new ModelReplied(new CommandId("1-0"), List.of(new TextBlock("hi")), StopReason.END_TURN, "m",
                new TokenUsage(1, 2, 3, 4).weighed(1, 1, 1, 1), null, null, null);
        assertFalse(codec.encode(plain).contains("weighted"));
    }

    // ---- 插件开关和中途换工具（core-tools 任务 15）----

    private static final String OLD_ASSEMBLED = "{\"command\":{\"value\":\"1-0\"},\"system\":\"sys\",\"tools\":[{\"name\":\"read_file\","
            + "\"description\":\"read\",\"schema\":{}}],\"sections\":[]}";

    @Test
    void enabledListsAndToolChangesSurviveARoundTrip() {
        ToolSpec read = new ToolSpec("read_file", "read", new Json.Obj(Map.of()));
        assertRoundTrip(ContextAssembledFixture.of(CMD, "sys", List.of(read), null, List.of(), List.of("read_file"), List.of("quota")));
        assertRoundTrip(ContextAssembledFixture.of(CMD, "sys", List.of(read), null, List.of(), List.of(), List.of()));
        assertRoundTrip(ContextAssembledFixture.of(CMD, "sys", List.of(read), null, List.of(), List.of("read_file"), List.of(),
                Map.of("read_file", "builtin", "browser_navigate", "mcp:browser")));
        assertRoundTrip(new ModelReplied(CMD, List.of(new ToolChangeBlock(List.of("write_file"), List.of("grep"))), StopReason.END_TURN,
                "m", null, null, null, null));
    }

    /** 不带名单时写出的行和加字段之前逐字节相同；旧行读出来名单是 null，表示全部开着。deferred 为 false 时不写出。 */
    @Test
    void contextWithoutListsWritesTheSameBytesAsBefore() {
        ContextAssembled old = ContextAssembledFixture.of(CMD, "sys", List.of(new ToolSpec("read_file", "read", new Json.Obj(Map.of()))), null,
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
        CompactionSettings legacy = CompactionSettingsFixture.of(200_000, 64_000, 20_000, 32_768, 4_096, 8_192);
        ContextAssembled old = ContextAssembledFixture.of(CMD, "sys", List.of(), legacy, List.of());
        String line = codec.encode(old);
        assertEquals("{\"command\":{\"value\":\"1-0\"},\"system\":\"sys\",\"tools\":[],\"compaction\":{\"window\":200000,"
                + "\"reserve\":64000,\"keepRecent\":20000,\"maxKeptResultBytes\":32768,\"maxSummarizedResultBytes\":4096,"
                + "\"summaryMaxTokens\":8192},\"sections\":[]}", line);
        CompactionSettings decoded = ((ContextAssembled) codec.decode(ContextAssembled.TYPE, 2, line)).compaction();
        assertEquals(legacy, decoded);
        assertEquals(136_000, decoded.threshold());
        assertEquals(3, decoded.bytesPerTokenOrLegacy());

        CompactionSettings chosen = new CompactionSettings(1_000_000, 64_000, 20_000, 32_768, 4_096, 8_192, 500_000, 1.5);
        assertRoundTrip(ContextAssembledFixture.of(CMD, "sys", List.of(), chosen, List.of()));
        assertTrue(codec.encode(ContextAssembledFixture.of(CMD, "sys", List.of(), chosen, List.of()))
                .contains("\"point\":500000,\"bytesPerToken\":1.5"));
    }

    @Test
    void anEmptyEnabledListIsNotTheSameAsNoList() {
        String none = codec.encode(ContextAssembledFixture.of(CMD, "sys", List.of(), null, List.of(), List.of(), List.of()));
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
        assertRoundTrip(UserPromptFixture.of(SUB, REQ, "", refs));
        assertRoundTrip(UserPromptFixture.join(SUB, REQ, "look", refs));
        assertRoundTrip(UserPromptFixture.of(SUB, REQ, "later", refs));
        assertRoundTrip(new ToolFinished(CMD, "c", "Image ...", false, null, List.of(IMAGE)));
        assertRoundTrip(new ModelReplied(CMD, List.of(new TextBlock("x"), new AttachmentBlock(IMAGE), new AttachmentBlock(PDF),
                new ToolResultBlock("c", "out", false, null, List.of(IMAGE))), StopReason.END_TURN, "m", null, null, null, null));
    }

    /** {@code kind} 在前，没有值的字段不写：没缩小过的图片没有 source*，不是 PDF 的文件没有 pages。 */
    @Test
    void attachmentRefsHaveKindFirstAndOmitEmptyFields() {
        String line = codec.encode(UserPromptFixture.join(SUB, REQ, "x", List.of(ImageRefFixture.of(IMAGE.id(), "a.png", "image/png", 1, 2, 3), TEXT)));
        assertTrue(line.contains("\"attachments\":[{\"kind\":\"image\",\"id\":\"sha256:" + HASH + "\",\"name\":\"a.png\","
                + "\"mediaType\":\"image/png\",\"bytes\":1,\"width\":2,\"height\":3},{\"kind\":\"file\","), line);
        assertTrue(line.endsWith("\"mediaType\":\"text/plain\",\"bytes\":5}],\"delivery\":\"JOIN\"}"), line);
    }

    /**
     * 模型回复和工具结果的写法一个字节都不变：39 份录音（{@code ReplayScenarioTest}）直接读这两种行。提问的写法见
     * {@link #anEmptyPromptWritesOnlyWhatItHas}。
     */
    @Test
    void modelRepliesAndToolResultsWriteTheSameBytesAsBefore() {
        String replied = "{\"command\":{\"value\":\"1-0\"},\"content\":[{\"kind\":\"toolResult\",\"toolCallId\":\"c\","
                + "\"text\":\"out\",\"error\":false}],\"stop\":\"END_TURN\",\"actualModel\":\"m\"}";
        ModelReplied reply = new ModelReplied(CMD, List.of(new ToolResultBlock("c", "out", false, null)), StopReason.END_TURN, "m",
                null, null, null, null);
        assertEquals(OLD_TOOL, codec.encode(ToolFinishedFixture.of(CMD, "c", "out", true, null)));
        assertEquals(replied, codec.encode(reply));
        assertEquals(List.of(), ((ToolFinished) codec.decode(ToolFinished.TYPE, 1, OLD_TOOL)).images());
        assertEquals(reply, codec.decode(ModelReplied.TYPE, 1, replied));
    }

    /** 旧程序读新行时忽略不认识的字段，不会把会话当成损坏。用一个现在也不认识的字段钉住这个默认行为。 */
    @Test
    void unknownFieldsAreIgnored() {
        String later = OLD_TOOL.substring(0, OLD_TOOL.length() - 1) + ",\"queuedFor\":{\"ms\":3}}";
        assertEquals(ToolFinishedFixture.of(CMD, "c", "out", true, null), codec.decode(ToolFinished.TYPE, 1, later));
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

    /** review-fixes 需求 3.6：语言表写出时一个值都不丢，已有三种的顺序不变。 */
    @Test
    void everyLanguageIsWrittenInAStableOrder() {
        java.util.Map<tantalum.value.text.Language, String> texts = new java.util.EnumMap<>(tantalum.value.text.Language.class);
        for (tantalum.value.text.Language language : tantalum.value.text.Language.values()) {
            texts.put(language, language.name());
        }
        java.util.Map<tantalum.value.text.Language, String> ordered = new EventCodec.OrderedLanguages().convert(texts);
        assertEquals(texts.keySet(), ordered.keySet());
        assertEquals(EventCodec.OrderedLanguages.FIRST, List.copyOf(ordered.keySet()).subList(0, 3));
    }

    // ---- 用户点名的技能（skills-wiki 任务 15）----

    private static final tantalum.value.event.SkillUse READ = new tantalum.value.event.SkillUse("pdf-tools",
            "<skill_content name=\"pdf-tools\">x</skill_content>", null);
    private static final tantalum.value.event.SkillUse FAILED = new tantalum.value.event.SkillUse("slow", null, "timeout");

    @Test
    void namedSkillsSurviveARoundTrip() {
        List<tantalum.value.event.SkillUse> skills = List.of(READ, FAILED);
        assertRoundTrip(new UserPrompt(SUB, REQ, "/pdf-tools go", List.of(), null, skills, List.of(), Delivery.QUEUE));
        assertRoundTrip(new UserPrompt(SUB, REQ, "/pdf-tools now", List.of(), null, skills, List.of(), Delivery.JOIN));
        assertRoundTrip(new ModelReplied(CMD, List.of(new TextBlock("x"), new tantalum.value.model.SkillBlock("pdf-tools", READ.content())),
                StopReason.END_TURN, "m", null, null, null, null));
    }

    /** memory-plugin 需求 1.2：插件附的内容随提交进 journal，读回来一样；块的 kind 是 attached。 */
    @Test
    void attachedTextsSurviveARoundTrip() {
        List<tantalum.value.model.AttachedBlock> attached = List.of(new tantalum.value.model.AttachedBlock("memory", "- 用户在做 Tantalum"));
        assertRoundTrip(new UserPrompt(SUB, REQ, "hi", List.of(), null, List.of(), attached, Delivery.QUEUE));
        String line = codec.encode(new UserPrompt(SUB, REQ, "later", List.of(), null, List.of(), attached, Delivery.QUEUE));
        assertTrue(line.contains("\"attached\":[{\"kind\":\"attached\",\"plugin\":\"memory\",\"text\":\"- 用户在做 Tantalum\"}]"), line);
        String block = codec.encode(new ModelReplied(CMD, List.of(attached.getFirst()), StopReason.END_TURN, "m", null, null, null, null));
        assertTrue(block.contains("{\"kind\":\"attached\",\"plugin\":\"memory\""), block);
    }

    /** 读到的只有 content，没读到的只有 error；skills 在行的最后。 */
    @Test
    void aNamedSkillWritesItsContentOrItsError() {
        String line = codec.encode(new UserPrompt(SUB, REQ, "x", List.of(), null, List.of(READ, FAILED), List.of(), Delivery.JOIN));
        assertTrue(line.contains("\"text\":\"x\",\"skills\":[{\"name\":\"pdf-tools\",\"content\":\"<skill_content name=\\\"pdf-tools\\\">x"
                + "</skill_content>\"},{\"name\":\"slow\",\"error\":\"timeout\"}]"), line);
        String block = codec.encode(new ModelReplied(CMD, List.of(new tantalum.value.model.SkillBlock("pdf-tools", "body")),
                StopReason.END_TURN, "m", null, null, null, null));
        assertTrue(block.contains("\"content\":[{\"kind\":\"skill\",\"name\":\"pdf-tools\",\"text\":\"body\"}]"), block);
    }

    /** 没有附件、技能、插件附的内容时一个字不多写，读出来是空列表；怎么送总是写着。 */
    @Test
    void anEmptyPromptWritesOnlyWhatItHas() {
        String queued = "{\"submission\":{\"value\":\"s\"},\"request\":{\"value\":\"r\"},\"text\":\"hi\",\"delivery\":\"QUEUE\"}";
        String joined = "{\"submission\":{\"value\":\"s\"},\"request\":{\"value\":\"r\"},\"text\":\"x\",\"delivery\":\"JOIN\"}";
        assertEquals(queued, codec.encode(UserPromptFixture.of(SUB, REQ, "hi")));
        assertEquals(joined, codec.encode(UserPromptFixture.join(SUB, REQ, "x")));
        UserPrompt read = (UserPrompt) codec.decode(UserPrompt.TYPE, 1, queued);
        assertEquals(List.of(), read.skills());
        assertEquals(List.of(), read.attachments());
        assertEquals(List.of(), read.attached());
    }
}
