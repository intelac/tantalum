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
import tantalum.value.event.Cancel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
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
    private final List<Long> snapshots = new ArrayList<>();
    private final List<SessionId> removed = new ArrayList<>();
    private long seq;

    private Evictor evictor(Duration idle, int max, int snapshotEvents) {
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
        return new Evictor(new Evictor.Settings(idle, max, snapshotEvents, NONE), residency, projection,
                slice -> snapshots.add(slice.seq()), clock);
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

    private void touch(Evictor evictor, SessionId session, KernelState before, KernelState after) {
        residency.resident(session);
        evictor.stepped(new EventEnvelope(seq++, clock.instant(), ALICE, session, null, new Cancel(new RequestId("r" + seq))), before, after);
    }

    @Test
    void anIdleSessionLeavesAfterItsSnapshotWasHandedOver() {
        Evictor evictor = evictor(Duration.ofMinutes(30), 1000, 200);
        KernelState state = state(idle(A));
        touch(evictor, A, state, state);
        assertEquals(state, evictor.sweep(state), "not idle for long enough");
        clock.millis += Duration.ofMinutes(30).toMillis();
        KernelState after = evictor.sweep(state);
        assertFalse(after.sessions().containsKey(A));
        assertEquals(List.of(A), removed);
        assertEquals(List.of(0L), snapshots);
        assertEquals(0, evictor.resident());
    }

    @Test
    void aSessionWithAnActiveRunOrQueuedSubmissionStays() {
        Evictor evictor = evictor(NONE, 1000, 200);
        KernelState state = state(idle(A).startRun(RunId.of(A, 1)), idle(B).withFollowUps(List.of("next")));
        touch(evictor, A, state, state);
        touch(evictor, B, state, state);
        KernelState after = evictor.sweep(state);
        assertTrue(after.sessions().containsKey(A), "active run");
        assertTrue(after.sessions().containsKey(B), "queued follow-up");
        assertEquals(List.of(), snapshots, "no snapshot while a run is active");
    }

    @Test
    void aPinnedSessionStaysUntilItsEventIsFinished() {
        Evictor evictor = evictor(NONE, 1000, 200);
        KernelState state = state(idle(A));
        touch(evictor, A, state, state);
        residency.pin(A);
        assertTrue(evictor.sweep(state).sessions().containsKey(A));
        evictor.finished(A);
        assertFalse(evictor.sweep(state).sessions().containsKey(A));
    }

    @Test
    void overTheLimitTheLeastRecentlyUsedLeaves() {
        Evictor evictor = evictor(null, 2, 200);
        KernelState state = state(idle(A), idle(B), idle(C));
        touch(evictor, A, state, state);
        touch(evictor, B, state, state);
        touch(evictor, C, state, state);
        touch(evictor, A, state, state);
        KernelState after = evictor.sweep(state);
        assertFalse(after.sessions().containsKey(B), "B was used least recently");
        assertTrue(after.sessions().containsKey(A));
        assertTrue(after.sessions().containsKey(C));
    }

    @Test
    void overTheLimitAnActiveSessionIsSkippedForTheNextOldest() {
        Evictor evictor = evictor(null, 2, 200);
        KernelState state = state(idle(A).startRun(RunId.of(A, 1)), idle(B), idle(C));
        touch(evictor, A, state, state);
        touch(evictor, B, state, state);
        touch(evictor, C, state, state);
        KernelState after = evictor.sweep(state);
        assertTrue(after.sessions().containsKey(A));
        assertFalse(after.sessions().containsKey(B));
    }

    @Test
    void aRunEndingWritesASnapshotOnlyAfterEnoughEvents() {
        Evictor evictor = evictor(null, 1000, 3);
        KernelState running = state(idle(A).startRun(RunId.of(A, 1)));
        KernelState ended = state(idle(A));
        touch(evictor, A, running, running);
        touch(evictor, A, running, ended);
        assertEquals(List.of(), snapshots, "only two events");
        touch(evictor, A, running, running);
        touch(evictor, A, running, running);
        touch(evictor, A, running, ended);
        assertEquals(List.of(4L), snapshots);
        touch(evictor, A, running, ended);
        assertEquals(List.of(4L), snapshots, "the counter restarted");
    }

    /** 载入后什么都没变就回收时，磁盘上的快照已经是最新的。 */
    @Test
    void evictionDoesNotRewriteASnapshotThatIsAlreadyCurrent() {
        Evictor evictor = evictor(NONE, 1000, 200);
        residency.resident(A);
        evictor.loaded(A, new CleanroomRing.Loaded(new SessionSlice(idle(A), List.of(), null, 7), 0));
        evictor.sweep(state(idle(A)));
        assertEquals(List.of(), snapshots);
        assertEquals(List.of(A), removed);
    }

    @Test
    void sweepsAreThrottled() {
        Evictor evictor = new Evictor(new Evictor.Settings(NONE, 1000, 200, Duration.ofSeconds(1)), residency, LoopFixtures.NO_PROJECTION, LoopFixtures.NO_SINK, clock);
        KernelState state = state(idle(A));
        evictor.sweep(state);
        touch(evictor, A, state, state);
        assertEquals(state, evictor.sweep(state));
        clock.millis += 1000;
        assertFalse(evictor.sweep(state).sessions().containsKey(A));
    }
}
