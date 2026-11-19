package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.shell.journal.Line;
import tantalum.value.decision.SessionChange;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;

/**
 * “不进记忆”要跟着账走（memory-final-plan 第 11 节）。2026-11-16 当天写的行没把它写进结果，重启后会话变回普通会话；
 * 读这样的行时按第一次提问里带的标记补上。新写的行自己带着（{@code SessionDirCodecTest}）。
 */
class OffRecordReplayTest {

    private static final Principal USER = new Principal("alice");
    private static final SessionId SESSION = new SessionId("s-1");

    private static Line line(Boolean offRecord, boolean created, SessionChange.Changed<Boolean> written) {
        UserPrompt prompt = new UserPrompt(new SubmissionId("s"), new RequestId("r"), new OwnerRef.OfSession(SESSION), "hi", List.of(), null,
                null, List.of(), List.of(), offRecord);
        SessionChange change = new SessionChange(SESSION, USER, created, List.of(), null, null, null, null, null, null, null, null, null,
                null, null, null, written, null, null);
        return new Line(new EventEnvelope(0, Instant.parse("2026-11-16T00:00:00Z"), USER, SESSION, null, prompt),
                new StepRecord(0, change, List.of(), List.of(), null, null, List.of(), List.of()), Map.of());
    }

    @Test
    void anOlderFirstPromptThatWasOffTheRecordStaysOffTheRecord() {
        Line old = line(true, true, null);
        StepRecord fixed = RecordReplay.offRecord(old);
        assertEquals(new SessionChange.Changed<>(true), fixed.session().offRecord());
        assertTrue(fixed.apply(old.envelope(), KernelState.empty()).newState().sessions().get(SESSION).offRecord());
    }

    @Test
    void otherLinesAreLeftAsTheyAre() {
        Line ordinary = line(null, true, null);
        assertSame(ordinary.record(), RecordReplay.offRecord(ordinary));
        assertNull(RecordReplay.offRecord(ordinary).session().offRecord());
        Line later = line(true, false, null);
        assertSame(later.record(), RecordReplay.offRecord(later), "only the first prompt decides");
        Line written = line(true, true, new SessionChange.Changed<>(true));
        assertSame(written.record(), RecordReplay.offRecord(written));
    }
}
