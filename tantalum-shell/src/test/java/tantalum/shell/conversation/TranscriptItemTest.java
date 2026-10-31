package tantalum.shell.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tantalum.shell.conversation.TranscriptItem.RunStatus;
import tantalum.shell.conversation.TranscriptItem.StepStatus;
import tantalum.shell.conversation.TranscriptItem.ToolStep;
import tantalum.shell.conversation.TranscriptItem.UserMessage;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.EndReason;

class TranscriptItemTest {

    private static final SessionId SESSION = new SessionId("s");
    private static final RunId RUN = new RunId("run-0");
    private static final Instant T0 = Instant.parse("2026-10-31T00:00:00Z");

    private static ToolCallBlock call() {
        return new ToolCallBlock("c1", "read_file", "{}", new Json.Obj(java.util.Map.of()));
    }

    @Test
    void idComesFromSeqAndIndexOnly() {
        assertEquals("7.2", TranscriptItem.id(7, 2));
        assertEquals("run:run-0", RunStatus.idOf(RUN));
    }

    @Test
    void toolStepKeepsItsIdThroughEveryTransition() {
        ToolStep waiting = ToolStep.waiting("3.1", SESSION, RUN, T0, call());
        ToolStep running = waiting.running("g-3", T0.plusSeconds(1));
        ToolStep done = running.finished(new ToolResultBlock("c1", "ok", false, null), T0.plusSeconds(4));

        assertEquals("3.1", done.id());
        assertEquals(StepStatus.WAITING, waiting.status());
        assertNull(waiting.elapsed());
        assertEquals(StepStatus.RUNNING, running.status());
        assertEquals("g-3", running.group());
        assertEquals(StepStatus.DONE, done.status());
        assertEquals(Duration.ofSeconds(3), done.elapsed());
        assertTrue(done.finished());
        assertFalse(running.finished());
    }

    @Test
    void errorResultIsFailedAndBlockedHasNoDuration() {
        ToolStep running = ToolStep.waiting("3.1", SESSION, RUN, T0, call()).running("g", T0);
        ToolStep failed = running.finished(new ToolResultBlock("c1", "boom", true, "TOOL_OUTCOME_UNKNOWN"), T0.plusSeconds(2));
        ToolStep blocked = ToolStep.waiting("3.2", SESSION, RUN, T0, call())
                .blocked(new ToolResultBlock("c1", "Tool call blocked: policy", true, null), T0);

        assertEquals(StepStatus.FAILED, failed.status());
        assertEquals("TOOL_OUTCOME_UNKNOWN", failed.result().code());
        assertEquals(StepStatus.BLOCKED, blocked.status());
        assertEquals(Duration.ZERO, blocked.elapsed());
        assertTrue(blocked.finished());
    }

    @Test
    void runStatusTracksPhaseAndEnd() {
        RunStatus status = RunStatus.started(SESSION, RUN, T0, "AwaitingContext")
                .inPhase("AwaitingModel", T0.plusSeconds(1))
                .ended(EndReason.COMPLETED, null, T0.plusSeconds(32));

        assertEquals("run:run-0", status.id());
        assertTrue(status.ended());
        assertEquals("Ended", status.phase());
        assertEquals(Duration.ofSeconds(32), status.elapsed());
    }

    @Test
    void queuedFollowUpBecomesActiveWhenItsRunStarts() {
        UserMessage queued = new UserMessage("9.0", SESSION, null, T0, "later", true);
        UserMessage started = queued.started(new RunId("run-12"));

        assertNull(queued.run());
        assertEquals("9.0", started.id());
        assertEquals(new RunId("run-12"), started.run());
        assertFalse(started.queued());
    }
}
