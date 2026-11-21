package tantalum.value.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.FailureReason;
import tantalum.value.model.StopReason;

class EventsTest {

    private static final CommandId CMD = new CommandId("1-0");
    private static final RequestId REQ = new RequestId("r");
    private static final SubmissionId SUB = new SubmissionId("s");
    private static final SessionId SESSION = new SessionId("session");

    @Test
    void everyEventNamesItsTypeAndVersion() {
        List<Event> events = List.of(
                UserPromptFixture.of(SUB, REQ, "hi"),
                UserPromptFixture.join(SUB, REQ, "hi"),
                new SessionCreated(tantalum.value.state.SessionKind.REGULAR, null, false, null),
                new Assignment("t1-0", SESSION, Root.USER, "do it", null),
                new Report("t1-0", tantalum.value.assignment.AssignmentOutcome.COMPLETED, "done", Root.USER, SESSION),
                new Cancel(REQ),
                ContextAssembledFixture.of(CMD, "system", List.of(), null, List.of()),
                new ModelReplied(CMD, List.of(), StopReason.END_TURN, "model", null, null, null, null),
                new ModelFailed(CMD, FailureReason.TIMEOUT, true, Duration.ZERO, "", "", null, null, null),
                new ModelInterrupted(CMD, "partial", null, null, null),
                ToolFinishedFixture.of(CMD, "call", "out", false, null),
                new TimerFired(CMD),
                new WorkDone(CMD, "citation", new Json.Null(), false, null, null));

        for (Event event : events) {
            assertEquals(event.getClass().getSimpleName(), event.type());
            // 组装上下文第 2 版加了工具来源（one-line-journal J5），其余都是第 1 版
            assertEquals(event instanceof ContextAssembled ? 2 : 1, event.version(), event.type());
        }
    }

    @Test
    void envelopeAssignsSeqWithoutChangingTheRest() {
        EventEnvelope envelope = new EventEnvelope(EventEnvelope.UNASSIGNED, Instant.EPOCH,
                new Principal("p"), SESSION, null, new Cancel(REQ));
        EventEnvelope written = envelope.withSeq(7);
        assertEquals(7, written.seq());
        assertEquals(envelope.event(), written.event());
        assertEquals(envelope.at(), written.at());
    }

    @Test
    void envelopeRequiresOriginAndEvent() {
        Principal principal = new Principal("p");
        Event event = new Cancel(REQ);
        assertThrows(NullPointerException.class, () -> new EventEnvelope(0, null, principal, SESSION, null, event));
        assertThrows(NullPointerException.class, () -> new EventEnvelope(0, Instant.EPOCH, null, SESSION, null, event));
        assertThrows(NullPointerException.class, () -> new EventEnvelope(0, Instant.EPOCH, principal, null, null, event));
        assertThrows(NullPointerException.class, () -> new EventEnvelope(0, Instant.EPOCH, principal, SESSION, null, null));
    }
}
