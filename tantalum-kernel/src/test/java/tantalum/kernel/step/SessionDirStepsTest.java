package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.value.decision.SessionChange;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.SessionState;

/** workspaces 任务 2（W3、需求 2.1、2.2）：会话的目录在第一次提问时记下，之后不变。 */
class SessionDirStepsTest {

    private static final CompactionSettings BIG = new CompactionSettings(10000, 200, 70, 60, 30, 500, 5000, 3);

    private static UserPrompt prompt(String text, String dir) {
        return new UserPrompt(new SubmissionId("sub-" + text), new RequestId("req-" + text), new OwnerRef.OfSession(LoopFixture.SESSION),
                text, List.of(), null, dir);
    }

    private static void ask(LoopFixture loop, String text, String dir) {
        loop.feed(null, prompt(text, dir));
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), BIG, List.of(), null, null, Map.of(), null));
        loop.feed(loop.runId(), CompactStepsTest.endTurn(loop, "a-" + text, 100));
    }

    @Test
    void theFirstPromptSetsTheDirectory() {
        LoopFixture loop = new LoopFixture();
        loop.feed(null, prompt("one", "/home/u/app"));
        assertEquals("/home/u/app", loop.session().dir());
    }

    @Test
    void laterPromptsDoNotChangeIt() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "one", "/home/u/app");
        ask(loop, "two", "/home/u/other");
        assertEquals("/home/u/app", loop.session().dir());
    }

    @Test
    void aSessionStartedWithoutADirectoryStaysWithout() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "one", null);
        ask(loop, "two", "/home/u/app");
        assertNull(loop.session().dir());
    }

    @Test
    void aQueuedPromptCarryingADirectoryIsIgnored() {
        LoopFixture loop = new LoopFixture();
        loop.feed(null, prompt("one", "/home/u/app"));
        loop.feed(null, prompt("two", "/home/u/other"));
        loop.feed(null, new FollowUpSubmitted(new SubmissionId("f"), new RequestId("f"), "three", List.of(), null));
        assertEquals("/home/u/app", loop.session().dir());
        assertEquals(2, loop.session().followUps().size());
    }

    @Test
    void theChangeRecordsTheDirectoryAndReplaysIt() {
        SessionState before = SessionState.start(LoopFixture.SESSION, LoopFixture.USER);
        SessionState after = before.withDir("/home/u/app");
        SessionChange change = SessionChange.between(before, after);
        assertEquals(new SessionChange.Changed<>("/home/u/app"), change.dir());
        assertEquals(after, change.applyTo(before));
        assertNull(SessionChange.between(after, after).dir());
    }
}
