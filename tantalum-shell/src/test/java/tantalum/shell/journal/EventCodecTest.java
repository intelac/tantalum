package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolCallBlock;
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
                new ContextAssembled(CMD, "system", List.of(new ToolSpec("grep", "d", complexJson()))),
                new ContextAssembled(CMD, "system", List.of(), "idx", new CompactionSettings(200_000, 64_000, 20_000, 32_768, 4_096, 8_192)),
                new tantalum.value.event.CompactRequested(SUB, REQ, "focus on the test results"),
                new tantalum.value.event.CompactRequested(SUB, REQ, null),
                new ModelReplied(CMD, content, StopReason.TOOL_USE, "m", new TokenUsage(1, 2, 3, 4)),
                new ModelReplied(CMD, List.of(), StopReason.END_TURN, "m", null),
                new ModelFailed(CMD, FailureReason.RATE_LIMITED, true, Duration.ofSeconds(30), "partial", "429"),
                new ModelFailed(CMD, FailureReason.REFUSAL, false, null, null, "cyber"),
                new ModelInterrupted(CMD, "partial"),
                new ToolFinished(CMD, "c", "out", false),
                new TimerFired(CMD),
                new WorkDone(CMD, "citation", complexJson(), false, "fix it"),
                new WorkDone(CMD, "citation", new Json.Null(), true, null));
        events.forEach(this::assertRoundTrip);
    }

    /** 压缩之前写下的 ContextAssembled 没有 compaction 字段，读出来是 null，这些会话不压缩。 */
    @Test
    void legacyContextAssembledWithoutCompactionStillDecodes() {
        ContextAssembled decoded = (ContextAssembled) codec.decode(ContextAssembled.TYPE, 1,
                "{\"command\":{\"value\":\"1-0\"},\"system\":\"s\",\"tools\":[]}");
        assertEquals("s", decoded.system());
        assertNull(decoded.dynamic());
        assertNull(decoded.compaction());
    }

    @Test
    void jsonKeepsKeyOrderAndNumberPrecision() {
        WorkDone event = new WorkDone(CMD, "w", complexJson(), false, null);
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
                new ModelReplied(CMD, List.of(bad, nullArgs), StopReason.TOOL_USE, "m", null)));
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
