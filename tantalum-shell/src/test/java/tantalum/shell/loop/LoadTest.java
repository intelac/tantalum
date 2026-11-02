package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tantalum.shell.journal.Sequence;
import tantalum.value.event.Cancel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.state.SessionState;

/** 通道载入冷会话：切片跟着第一个事件走，失败不扩散，慢载入只拖住这一个用户。 */
class LoadTest {

    private record Reached(EventEnvelope envelope, CleanroomRing.Load load) {}

    private final List<Reached> reached = new CopyOnWriteArrayList<>();
    private final Residency residency = new Residency();
    private final Map<Principal, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Sequence sequence = principal -> counters.computeIfAbsent(principal, ignored -> new AtomicLong(100)).getAndIncrement();

    private InputRing start(LoopFixtures.Load loader) {
        LaneDispatcher[] holder = new LaneDispatcher[1];
        holder[0] = new LaneDispatcher(256, sequence, (principal, session, events) -> { }, (envelope, usage, load) -> {
            reached.add(new Reached(envelope, load));
            holder[0].released();
            return true;
        }, LoopFixtures.loader(loader), residency);
        InputRing input = LoopFixtures.inputRing(1024, holder[0]);
        input.start();
        return input;
    }

    private static EventEnvelope event(String user, String session) {
        return new EventEnvelope(EventEnvelope.UNASSIGNED, Instant.EPOCH, new Principal(user), new SessionId(session), null, new Cancel(new RequestId("r")));
    }

    private void await(int count) throws InterruptedException {
        for (int i = 0; i < 1000 && reached.size() < count; i++) {
            Thread.sleep(5);
        }
        assertEquals(count, reached.size());
    }

    private static CleanroomRing.Loaded slice(SessionId session, Principal principal) {
        return new CleanroomRing.Loaded(new SessionSlice(SessionState.start(session, principal), List.of(), null, 1), 1);
    }

    @Test
    void aSliceRidesOnTheFirstEventOnlyAndAskedForHistoryBeforeIt() throws Exception {
        AtomicLong before = new AtomicLong();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch hold = new CountDownLatch(1);
        InputRing input = start((principal, session, beforeSeq) -> {
            hold.await();
            loads.incrementAndGet();
            before.set(beforeSeq);
            return Optional.of(slice(session, principal));
        });
        for (int i = 0; i < 3; i++) {
            assertTrue(input.tryPublish(event("alice", "s")));
        }
        Thread.sleep(50);
        hold.countDown();
        await(3);
        assertNotNull(reached.get(0).load());
        assertNull(reached.get(1).load());
        assertNull(reached.get(2).load());
        assertEquals(reached.get(0).envelope().seq(), before.get(), "history means everything before the first new event");
        assertTrue(input.tryPublish(event("alice", "s")));
        await(4);
        assertNull(reached.get(3).load(), "already resident");
        assertEquals(1, loads.get());
        assertEquals(4, residency.pins(new SessionId("s")), "nothing has passed the cleanroom in this test");
    }

    @Test
    void aSlowLoadDoesNotHoldBackOtherUsers() throws Exception {
        CountDownLatch unstick = new CountDownLatch(1);
        InputRing input = start((principal, session, beforeSeq) -> {
            if (principal.value().equals("slow")) {
                unstick.await();
            }
            return Optional.empty();
        });
        assertTrue(input.tryPublish(event("slow", "big")));
        Thread.sleep(50);
        for (int i = 0; i < 20; i++) {
            assertTrue(input.tryPublish(event("fast", "s" + i)));
        }
        await(20);
        assertTrue(reached.stream().allMatch(item -> item.envelope().principal().value().equals("fast")));
        unstick.countDown();
        await(21);
    }

    /** 同一用户内严格按序，所以同一用户的其他会话要等这次载入。这是设计决定，这里钉住它。 */
    @Test
    void aSlowLoadKeepsTheOrderWithinTheUser() throws Exception {
        CountDownLatch unstick = new CountDownLatch(1);
        InputRing input = start((principal, session, beforeSeq) -> {
            if (session.value().equals("big")) {
                unstick.await();
            }
            return Optional.empty();
        });
        assertTrue(input.tryPublish(event("alice", "big")));
        Thread.sleep(50);
        assertTrue(input.tryPublish(event("alice", "small")));
        Thread.sleep(100);
        assertEquals(0, reached.size());
        unstick.countDown();
        await(2);
        assertEquals("big", reached.get(0).envelope().session().value());
    }

    @Test
    void aFailedLoadMarksEveryEventOfThatSessionOnlyAndDoesNotRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        InputRing input = start((principal, session, beforeSeq) -> {
            if (session.value().equals("bad")) {
                attempts.incrementAndGet();
                throw new IllegalStateException("corrupt");
            }
            return Optional.empty();
        });
        assertTrue(input.tryPublish(event("alice", "bad")));
        assertTrue(input.tryPublish(event("alice", "good")));
        await(2);
        assertTrue(input.tryPublish(event("alice", "bad")));
        await(3);
        for (Reached item : reached) {
            boolean bad = item.envelope().session().value().equals("bad");
            assertEquals(bad, item.load() instanceof CleanroomRing.Failed, item.envelope().session().value());
        }
        assertEquals(1, attempts.get());
        assertTrue(residency.failure(new SessionId("bad")).contains("corrupt"));
        assertNull(residency.failure(new SessionId("good")));
    }
}
