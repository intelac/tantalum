package tantalum.value.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private static final OwnerRef OWNER = new OwnerRef.OfSession(SESSION);

    @Test
    void runUpdatesReturnNewObjects() {
        RunState run = RunState.start(REF, OWNER);
        RunState changed = run.withPhase(new Awaiting(Set.of(), "AwaitingModel"))
                .withContext("sys", List.of())
                .withTools(new ToolRound(List.of(), 0))
                .withPendingSteers(List.of(Message.user("x")))
                .withRetries(2);

        assertEquals(new Deciding(), run.phase());
        assertNull(run.system());
        assertEquals("sys", changed.system());
        assertEquals(2, changed.retries());
        assertEquals(1, changed.pendingSteers().size());
        assertEquals(0, changed.tools().slots().size());
        assertEquals(REF, changed.ref());
        assertEquals(OWNER, changed.owner());
        assertFalse(changed.ended());
        assertTrue(changed.withPhase(new Ended(EndReason.COMPLETED, null)).ended());
    }

    @Test
    void runRequiresIdentity() {
        assertThrows(NullPointerException.class, () -> RunState.start(null, OWNER));
        assertThrows(NullPointerException.class, () -> RunState.start(REF, null));
        assertThrows(NullPointerException.class,
                () -> new RunState(REF, OWNER, null, null, List.of(), null, List.of(), 0, null, null, false, false));
    }

    @Test
    void sessionAppendsWithoutChangingTheOriginal() {
        SessionState session = SessionState.start(SESSION, USER);
        SessionState changed = session.append(Message.user("a"))
                .withActiveRun(REF.run())
                .withFollowUps(List.of("next"));

        assertEquals(0, session.messages().size());
        assertEquals(1, changed.messages().size());
        assertEquals(REF.run(), changed.activeRun());
        assertEquals(List.of("next"), changed.followUps());
        assertThrows(NullPointerException.class, () -> SessionState.start(null, USER));
        assertThrows(NullPointerException.class, () -> SessionState.start(SESSION, null));
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
        RunState active = RunState.start(REF, OWNER);
        RunRef otherRef = new RunRef(USER, SESSION, new RunId("other"));
        RunState ended = RunState.start(otherRef, OWNER).withPhase(new Ended(EndReason.FAILED, "x"));

        KernelState state = empty.with(active).with(ended)
                .with(SessionState.start(SESSION, USER))
                .with(PrincipalState.start(USER));

        assertEquals(0, empty.runs().size());
        assertEquals(1, state.activeRunCount());
        assertEquals(1, state.sessions().size());
        assertEquals(1, state.principals().size());
    }
}
