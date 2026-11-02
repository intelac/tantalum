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
import tantalum.value.state.OwnerRef;

class EventsTest {

    private static final CommandId CMD = new CommandId("1-0");
    private static final RequestId REQ = new RequestId("r");
    private static final SubmissionId SUB = new SubmissionId("s");
    private static final SessionId SESSION = new SessionId("session");

    @Test
    void everyEventNamesItsTypeAndVersion() {
        List<Event> events = List.of(
                new UserPrompt(SUB, REQ, new OwnerRef.OfSession(SESSION), "hi"),
                new SteerSubmitted(SUB, REQ, "hi"),
                new FollowUpSubmitted(SUB, REQ, "hi"),
                new Cancel(REQ),
                new ContextAssembled(CMD, "system", List.of(), null, List.of()),
                new ModelReplied(CMD, List.of(), StopReason.END_TURN, "model", null),
                new ModelFailed(CMD, FailureReason.TIMEOUT, true, Duration.ZERO, "", ""),
                new ModelInterrupted(CMD, "partial"),
                new ToolFinished(CMD, "call", "out", false),
                new TimerFired(CMD),
                new WorkDone(CMD, "citation", new Json.Null(), false, null));

        for (Event event : events) {
            assertEquals(event.getClass().getSimpleName(), event.type());
            assertEquals(1, event.version());
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
