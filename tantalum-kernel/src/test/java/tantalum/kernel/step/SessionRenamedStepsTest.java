package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.value.decision.Decision;
import tantalum.value.event.SessionRenamed;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.KernelState;

/** workspaces 任务 3（W7、需求 5.5）：改名进账，内核不改任何状态，运行中也一样。 */
class SessionRenamedStepsTest {

    private static SessionRenamed renamed(String title) {
        return new SessionRenamed(new SubmissionId("sub-" + title), new RequestId("req-" + title), title);
    }

    @Test
    void renamingWhileARunWaitsForTheModelChangesNothing() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("hello");
        KernelState before = loop.state;
        String phase = loop.phase();
        Decision decision = loop.feed(null, renamed("Quattro review"));
        assertEquals(before, loop.state);
        assertEquals(phase, loop.phase());
        assertTrue(decision.commands().isEmpty());
    }

    @Test
    void renamingAnIdleSessionChangesNothing() {
        LoopFixture loop = new LoopFixture();
        KernelState before = loop.state;
        loop.feed(null, renamed(""));
        assertEquals(before, loop.state);
    }
}
