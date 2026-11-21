package tantalum.value.state;

import tantalum.value.event.Root;
import tantalum.value.event.Input;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.Message;
import tantalum.value.model.TokenUsage;

class StateTreeTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId SESSION = new SessionId("s");
    private static final RunRef REF = new RunRef(USER, SESSION, new RunId("r"));
    private static final RunCause CAUSE = RunCause.prompt();

    @Test
    void runUpdatesReturnNewObjects() {
        RunState run = RunState.start(REF, CAUSE);
        RunState changed = run.withPhase(new Awaiting(Set.of(), "AwaitingModel"))
                .withTools(new ToolRound(List.of(), 0))
                .withRetries(2);

        assertEquals(new Deciding(), run.phase());
        assertEquals(2, changed.retries());
        assertEquals(0, changed.tools().slots().size());
        assertEquals(REF, changed.ref());
        assertEquals(CAUSE, changed.cause());
        assertFalse(changed.isEnded());
        assertTrue(changed.withPhase(new Ended(EndReason.COMPLETED, null)).isEnded());
    }

    @Test
    void runRequiresIdentity() {
        assertThrows(NullPointerException.class, () -> RunState.start(null, CAUSE));
        assertThrows(NullPointerException.class, () -> RunState.start(REF, null));
        assertThrows(NullPointerException.class,
                () -> new RunState(REF, CAUSE, null, new RunState.Recovery(null, false, false), new RunState.Models(null, null)));
    }

    @Test
    void sessionAppendsWithoutChangingTheOriginal() {
        SessionState session = SessionStateFixture.regular(SESSION, USER);
        SessionState changed = session.append(Message.user("a"))
                .startRun(REF.run())
                .enqueue(new InboxItem(1, Input.Kind.PROMPT, Root.USER, Message.user("next"), null, false, null, null));

        assertEquals(0, session.messages().size());
        assertEquals(1, changed.messages().size());
        assertEquals(REF.run(), changed.activeRun());
        assertEquals(List.of(new InboxItem(1, Input.Kind.PROMPT, Root.USER, Message.user("next"), null, false, null, null)), changed.inbox());
        assertThrows(NullPointerException.class, () -> SessionStateFixture.regular(null, USER));
        assertThrows(NullPointerException.class, () -> SessionStateFixture.regular(SESSION, null));
        assertThrows(NullPointerException.class, () -> SessionState.start(SESSION, USER, null, null, null, false, null));
        assertThrows(IllegalArgumentException.class, () -> SessionState.start(SESSION, USER, SessionKind.HELPER, null, null, false, null),
                "a helper says whose helper it is");
        assertThrows(IllegalArgumentException.class,
                () -> SessionState.start(SESSION, USER, SessionKind.REGULAR, new HelperOf(SESSION, "t1-0", null, null, null), null, false, null));
    }

    @Test
    void usageAccumulatesPerDay() {
        LocalDate day = LocalDate.of(2026, 10, 31);
        PrincipalState principal = PrincipalState.start(USER)
                .add(day, new TokenUsage(1, 2, 3, 4))
                .add(day, new TokenUsage(1, 1, 1, 1));

        assertEquals(new TokenUsage(2, 3, 4, 5), principal.usageOn(day));
        assertEquals(new TokenUsage(0, 0, 0, 0), principal.usageOn(day.plusDays(1)));
        assertThrows(NullPointerException.class, () -> PrincipalState.start(null));
    }

    @Test
    void kernelStateCountsActiveRuns() {
        KernelState empty = KernelState.empty();
        RunState active = RunState.start(REF, CAUSE);
        RunRef otherRef = new RunRef(USER, SESSION, new RunId("other"));
        RunState ended = RunState.start(otherRef, CAUSE).withPhase(new Ended(EndReason.FAILED, "x"));

        KernelState state = empty.with(active).with(ended)
                .with(SessionStateFixture.regular(SESSION, USER))
                .with(PrincipalState.start(USER));

        assertEquals(0, empty.runs().size());
        assertEquals(1, state.activeRunCount());
        assertEquals(1, state.sessions().size());
        assertEquals(1, state.principals().size());
    }
}
