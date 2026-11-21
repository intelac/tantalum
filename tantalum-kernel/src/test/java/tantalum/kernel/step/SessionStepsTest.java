package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.Replay;
import tantalum.value.decision.Decision;
import tantalum.value.decision.SessionChange;
import tantalum.value.event.SessionCreated;
import tantalum.value.event.UserPromptFixture;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.Message;
import tantalum.value.state.HelperOf;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionKind;
import tantalum.value.state.SessionState;

/**
 * session-model 需求 1：会话有创建事件。种类、目录、是不是“不进记忆”、帮手的出身在建的时候定下，之后不变；
 * 没建的会话什么都不收；再建一次什么都不改；记进变化，回放一样。（取代“第一次提问定目录、定不进记忆”的两个测试。）
 */
class SessionStepsTest {

    private static final SessionId OTHER = new SessionId("s2");

    private static tantalum.value.event.UserPrompt prompt(String text) {
        return UserPromptFixture.of(new SubmissionId("sub-" + text), new RequestId("req-" + text), text);
    }

    @Test
    void creationFixesTheKindTheDirectoryAndOffRecord() {
        LoopFixture loop = new LoopFixture();
        Decision created = loop.create(OTHER, new SessionCreated(SessionKind.MAIN, "/home/u/app", true, null));

        SessionState session = loop.state.sessions().get(OTHER);
        assertEquals(SessionKind.MAIN, session.kind());
        assertEquals("/home/u/app", session.dir());
        assertTrue(session.offRecord());
        assertNull(session.helper());
        assertTrue(session.messages().isEmpty());
        assertTrue(created.commands().isEmpty(), "creating a session starts nothing");

        assertEquals(SessionKind.REGULAR, loop.session().kind(), "the fixture's own session is an ordinary one");
        assertNull(loop.session().dir());
        assertFalse(loop.session().offRecord());
    }

    @Test
    void aHelperSessionRemembersWhoseHelperItIs() {
        LoopFixture loop = new LoopFixture();
        HelperOf helper = new HelperOf(LoopFixture.SESSION, "t1-0", "reviewer", "You review code.", java.util.List.of("read"));
        loop.create(OTHER, new SessionCreated(SessionKind.HELPER, "/home/u/app", false, helper));
        assertEquals(SessionKind.HELPER, loop.state.sessions().get(OTHER).kind());
        assertEquals(helper, loop.state.sessions().get(OTHER).helper());
    }

    @Test
    void creatingASessionAgainChangesNothing() {
        LoopFixture loop = new LoopFixture();
        loop.create(OTHER, new SessionCreated(SessionKind.REGULAR, "/home/u/app", false, null));
        KernelState before = loop.state;
        Decision again = loop.create(OTHER, new SessionCreated(SessionKind.MAIN, "/home/u/other", true, null));
        assertSame(before, again.newState());
        assertEquals("/home/u/app", loop.state.sessions().get(OTHER).dir());
    }

    @Test
    void aSessionThatWasNeverCreatedTakesNothing() {
        LoopFixture loop = new LoopFixture();
        KernelState before = loop.state;
        Decision decision = loop.feed(OTHER, null, prompt("hi"));
        assertSame(before, decision.newState());
        assertTrue(decision.commands().isEmpty());
        assertNull(loop.state.sessions().get(OTHER), "a prompt does not create the session any more");
    }

    @Test
    void whatCreationFixedStaysThroughEverythingThatFollows() {
        LoopFixture loop = new LoopFixture();
        loop.create(OTHER, new SessionCreated(SessionKind.REGULAR, "/home/u/app", true, null));
        loop.feed(OTHER, null, prompt("one"));
        loop.feed(OTHER, null, prompt("two"));
        SessionState session = loop.state.sessions().get(OTHER);
        assertEquals("/home/u/app", session.dir());
        assertTrue(session.offRecord());
        assertEquals(1, session.inbox().size(), "the second prompt waits behind the run the first one started");
    }

    @Test
    void theChangeRecordsTheCreationAndPutsItBack() {
        SessionState after = SessionState.start(OTHER, LoopFixture.USER, SessionKind.MAIN, null, "/home/u/app", true);
        SessionChange change = SessionChange.between(null, after);
        assertTrue(change.created());
        assertEquals(SessionKind.MAIN, change.kind());
        assertEquals("/home/u/app", change.dir());
        assertTrue(change.offRecord());
        assertEquals(after, change.applyTo(null));

        SessionState grown = after.append(Message.user("x"));
        SessionChange later = SessionChange.between(after, grown);
        assertFalse(later.created());
        assertNull(later.kind(), "only the step that creates a session writes what is fixed then");
        assertEquals(grown, later.applyTo(after));

        // 建好不变：种类、目录、不进记忆变了，记不下来；没建就改、建两次，放不回去
        assertThrows(IllegalStateException.class,
                () -> SessionChange.between(after, SessionState.start(OTHER, LoopFixture.USER, SessionKind.REGULAR, null, "/home/u/app", true)));
        assertThrows(IllegalStateException.class,
                () -> SessionChange.between(after, SessionState.start(OTHER, LoopFixture.USER, SessionKind.MAIN, null, "/home/u/other", true)));
        assertThrows(IllegalStateException.class,
                () -> SessionChange.between(after, SessionState.start(OTHER, LoopFixture.USER, SessionKind.MAIN, null, "/home/u/app", false)));
        assertThrows(IllegalStateException.class, () -> later.applyTo(null));
        assertThrows(IllegalStateException.class, () -> change.applyTo(after));
    }

    @Test
    void replayCreatesTheSameSessions() {
        LoopFixture loop = new LoopFixture();
        loop.create(OTHER, new SessionCreated(SessionKind.MAIN, "/home/u/app", true, null));
        loop.feed(OTHER, null, prompt("one"));
        loop.prompt("two");
        assertEquals(loop.state, Replay.run(loop.kernel, loop.journal).state());
    }
}
