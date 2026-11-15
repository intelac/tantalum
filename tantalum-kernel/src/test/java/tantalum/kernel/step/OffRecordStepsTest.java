package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.value.decision.SessionChange;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.SessionState;

/** memory-final-plan 第 11 节：“不进记忆”和工作目录一样，只有会话的第一次提问能定下，之后不变；记进变化，回放一样。 */
class OffRecordStepsTest {

    private static final CompactionSettings BIG = new CompactionSettings(10000, 200, 70, 60, 30, 500, 5000, 3);

    private static UserPrompt prompt(String text, boolean offRecord) {
        return new UserPrompt(new SubmissionId("sub-" + text), new RequestId("req-" + text), new OwnerRef.OfSession(LoopFixture.SESSION),
                text, List.of(), null, null, List.of(), List.of(), offRecord ? Boolean.TRUE : null);
    }

    private static void ask(LoopFixture loop, String text, boolean offRecord) {
        loop.feed(null, prompt(text, offRecord));
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), BIG, List.of(), null, null, Map.of(), null));
        loop.feed(loop.runId(), CompactStepsTest.endTurn(loop, "a-" + text, 100));
    }

    @Test
    void theFirstPromptMakesTheSessionOffRecordForGood() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "one", true);
        ask(loop, "two", false);
        assertTrue(loop.session().offRecord());
    }

    @Test
    void aLaterPromptCannotMakeASessionOffRecord() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "one", false);
        ask(loop, "two", true);
        assertFalse(loop.session().offRecord());
    }

    @Test
    void falseIsNotWrittenSoOldLinesStayTheSame() {
        assertNull(prompt("x", false).offRecord());
        assertNull(new UserPrompt(new SubmissionId("s"), new RequestId("r"), new OwnerRef.OfSession(LoopFixture.SESSION), "x", List.of(),
                null, null, List.of(), List.of(), Boolean.FALSE).offRecord());
    }

    @Test
    void theChangeRecordsItAndReplaysIt() {
        SessionState before = SessionState.start(LoopFixture.SESSION, LoopFixture.USER);
        SessionState after = before.withDir("/home/u/app").withOffRecord();
        SessionChange change = SessionChange.between(before, after);
        assertEquals(new SessionChange.Changed<>(true), change.offRecord());
        assertEquals(after, change.applyTo(before));
        assertNull(SessionChange.between(after, after).offRecord());
        assertTrue(after.withNote("w", new tantalum.value.json.Json.Obj(Map.of())).withDir("/x").offRecord(), "other changes keep it");
    }
}
