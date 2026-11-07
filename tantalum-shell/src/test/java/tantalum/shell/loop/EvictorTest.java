package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.conversation.Projection;
import tantalum.shell.trace.Trace;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.model.Message;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionState;

class EvictorTest {

    private static final Principal ALICE = new Principal("alice");
    private static final SessionId A = new SessionId("a");
    private static final SessionId B = new SessionId("b");
    private static final SessionId C = new SessionId("c");
    private static final Duration NONE = Duration.ZERO;

    private final class TestClock extends Clock {
        long millis = 1_000_000;

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private final TestClock clock = new TestClock();
    private final Residency residency = new Residency();
    private final List<SessionId> removed = new ArrayList<>();

    private Evictor evictor(Duration idle, int max) {
        Projection projection = new Projection() {
            @Override
            public List<tantalum.shell.conversation.TranscriptItem> apply(EventEnvelope envelope, tantalum.value.decision.Decision decision) {
                return List.of();
            }

            @Override
            public void remove(SessionId session) {
                removed.add(session);
            }
        };
        return new Evictor(new Evictor.Settings(idle, max, NONE), residency, new Projections(projection, new Trace()), clock);
    }

    private static SessionState idle(SessionId id) {
        return SessionState.start(id, ALICE);
    }

    private static KernelState state(SessionState... sessions) {
        KernelState state = KernelState.empty();
        for (SessionState session : sessions) {
            state = state.with(session);
        }
        return state;
    }

    private void touch(Evictor evictor, SessionId session) {
        residency.resident(session);
        evictor.used(session);
    }

    @Test
    void anIdleSessionLeavesAfterTheIdleTimeout() {
        Evictor evictor = evictor(Duration.ofMinutes(30), 1000);
        KernelState state = state(idle(A));
        touch(evictor, A);
        assertEquals(state, evictor.sweep(state), "not idle for long enough");
        clock.millis += Duration.ofMinutes(30).toMillis();
        KernelState after = evictor.sweep(state);
        assertFalse(after.sessions().containsKey(A));
        assertEquals(List.of(A), removed);
        assertEquals(0, evictor.resident());
    }

    /** 按会话记在 shell 里的东西（先读后写的记录）随会话一起丢掉。 */
    @Test
    void theEvictionCallbackHearsAboutEachEvictedSession() {
        List<SessionId> evicted = new ArrayList<>();
        Evictor evictor = new Evictor(new Evictor.Settings(Duration.ofMinutes(30), 1000, NONE), residency,
                new Projections(new tantalum.shell.conversation.Transcript(), new Trace()), clock, evicted::add);
        KernelState state = state(idle(A));
        touch(evictor, A);
        evictor.sweep(state);
        assertEquals(List.of(), evicted);
        clock.millis += Duration.ofMinutes(30).toMillis();
        evictor.sweep(state);
        assertEquals(List.of(A), evicted);
    }

    @Test
    void aSessionWithAnActiveRunOrQueuedSubmissionStays() {
        Evictor evictor = evictor(NONE, 1000);
        KernelState state = state(idle(A).startRun(RunId.of(A, 1)), idle(B).withFollowUps(List.of(Message.user("next"))));
        touch(evictor, A);
        touch(evictor, B);
        KernelState after = evictor.sweep(state);
        assertTrue(after.sessions().containsKey(A), "active run");
        assertTrue(after.sessions().containsKey(B), "queued follow-up");
    }

    @Test
    void aPinnedSessionStaysUntilItsEventIsFinished() {
        Evictor evictor = evictor(NONE, 1000);
        KernelState state = state(idle(A));
        touch(evictor, A);
        residency.pin(A);
        assertTrue(evictor.sweep(state).sessions().containsKey(A));
        evictor.finished(A);
        assertFalse(evictor.sweep(state).sessions().containsKey(A));
    }

    @Test
    void overTheLimitTheLeastRecentlyUsedLeaves() {
        Evictor evictor = evictor(null, 2);
        KernelState state = state(idle(A), idle(B), idle(C));
        touch(evictor, A);
        touch(evictor, B);
        touch(evictor, C);
        touch(evictor, A);
        KernelState after = evictor.sweep(state);
        assertFalse(after.sessions().containsKey(B), "B was used least recently");
        assertTrue(after.sessions().containsKey(A));
        assertTrue(after.sessions().containsKey(C));
    }

    @Test
    void overTheLimitAnActiveSessionIsSkippedForTheNextOldest() {
        Evictor evictor = evictor(null, 2);
        KernelState state = state(idle(A).startRun(RunId.of(A, 1)), idle(B), idle(C));
        touch(evictor, A);
        touch(evictor, B);
        touch(evictor, C);
        KernelState after = evictor.sweep(state);
        assertTrue(after.sessions().containsKey(A));
        assertFalse(after.sessions().containsKey(B));
    }

    @Test
    void sweepsAreThrottled() {
        Evictor evictor = new Evictor(new Evictor.Settings(NONE, 1000, Duration.ofSeconds(1)), residency,
                new Projections(LoopFixtures.NO_PROJECTION, new Trace()), clock);
        KernelState state = state(idle(A));
        evictor.sweep(state);
        touch(evictor, A);
        assertEquals(state, evictor.sweep(state));
        clock.millis += 1000;
        assertFalse(evictor.sweep(state).sessions().containsKey(A));
    }

    /** waiting-state D3：停在等用户回答的运行没有在途命令，会话可以走；等命令结果的不能。 */
    @Test
    void aRunWaitingForAnAnswerLetsTheSessionLeave() {
        Evictor evictor = evictor(NONE, 1000);
        tantalum.value.id.RunId waitingRun = tantalum.value.id.RunId.of(A, 1);
        tantalum.value.id.RunId busyRun = tantalum.value.id.RunId.of(B, 1);
        tantalum.value.question.Question question = new tantalum.value.question.Question("q", tantalum.value.text.LocalText.of("Go on?"), null,
                List.of(new tantalum.value.question.Question.Choice("go", tantalum.value.text.LocalText.of("Go"))));
        tantalum.value.state.RunState waiting = tantalum.value.state.RunState.start(new tantalum.value.id.RunRef(ALICE, A, waitingRun),
                new tantalum.value.state.OwnerRef.OfSession(A)).withPhase(new tantalum.value.state.AwaitingAnswer(question,
                tantalum.value.hook.HookPoint.BEFORE_TOOL_CALL, null, null));
        tantalum.value.state.RunState busy = tantalum.value.state.RunState.start(new tantalum.value.id.RunRef(ALICE, B, busyRun),
                new tantalum.value.state.OwnerRef.OfSession(B)).withPhase(new tantalum.value.state.Awaiting(
                java.util.Set.of(new tantalum.value.id.CommandId("c")), "AwaitingTools"));
        KernelState state = state(idle(A).startRun(waitingRun), idle(B).startRun(busyRun)).with(waiting).with(busy);
        touch(evictor, A);
        touch(evictor, B);
        KernelState after = evictor.sweep(state);
        assertFalse(after.sessions().containsKey(A), "waiting for an answer: evictable");
        assertFalse(after.runs().containsKey(waitingRun), "its run leaves with it");
        assertTrue(after.sessions().containsKey(B), "waiting for a command result: stays");
    }
}
