package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tantalum.value.event.Cancel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;

class UserLaneTest {

    private static final Principal ALICE = new Principal("alice");

    /** 记录下游收到什么。 */
    private static final class Recorder implements UserLane.Exit {
        final List<EventEnvelope> published = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final AtomicLong discarded = new AtomicLong();
        volatile boolean halted;
        volatile boolean accept = true;

        @Override public boolean halted() { return halted; }
        @Override public boolean publish(EventEnvelope envelope) {
            if (accept) {
                published.add(envelope);
            }
            return accept;
        }
        @Override public void failed(Throwable failure) {
            failures.add(failure);
            halted = true;
        }
        @Override public void discarded(int count) { discarded.addAndGet(count); }

        void await(int count) throws InterruptedException {
            for (int i = 0; i < 1000 && published.size() + discarded.get() < count; i++) {
                Thread.sleep(5);
            }
            assertEquals(count, published.size() + discarded.get());
        }
    }

    private final Recorder exit = new Recorder();
    private final AtomicLong counter = new AtomicLong();
    private final Sequence sequence = principal -> counter.getAndIncrement();

    private static EventEnvelope event(String session, String request) {
        return new EventEnvelope(EventEnvelope.UNASSIGNED, Instant.EPOCH, ALICE, new SessionId(session), null,
                new Cancel(new RequestId(request)));
    }

    private static String request(EventEnvelope envelope) {
        return ((Cancel) envelope.event()).request().value();
    }

    @Test
    void eventsArePublishedInArrivalOrderWithIncreasingSeqAcrossSessions() throws Exception {
        UserLane lane = new UserLane(ALICE, sequence, (p, s, events) -> {
            try {
                Thread.sleep(s.value().equals("a") ? 3 : 0);
            } catch (InterruptedException stop) {
                throw new IOException(stop);
            }
        }, exit);
        int total = 300;
        for (int i = 0; i < total; i++) {
            lane.offer(event(i % 3 == 0 ? "a" : i % 3 == 1 ? "b" : "c", "r" + i));
        }
        exit.await(total);
        for (int i = 0; i < total; i++) {
            assertEquals("r" + i, request(exit.published.get(i)));
            assertEquals(i, exit.published.get(i).seq());
        }
    }

    /** 全部会话都写完才放行一批：不会有事件比同一批更早的事件先到内核。 */
    @Test
    void nothingIsPublishedUntilEverySessionOfTheBatchIsWritten() throws Exception {
        CountDownLatch slowWrite = new CountDownLatch(1);
        CountDownLatch writing = new CountDownLatch(2);
        CountDownLatch hold = new CountDownLatch(1);
        // 先用一个事件占住通道，让后两个进同一批。
        UserLane held = new UserLane(ALICE, sequence, (p, s, events) -> {
            if (s.value().equals("first")) {
                try {
                    hold.await();
                } catch (InterruptedException stop) {
                    throw new IOException(stop);
                }
                return;
            }
            writing.countDown();
            if (s.value().equals("slow")) {
                try {
                    slowWrite.await();
                } catch (InterruptedException stop) {
                    throw new IOException(stop);
                }
            }
        }, exit);
        held.offer(event("first", "r0"));
        Thread.sleep(50);
        held.offer(event("slow", "r1"));
        held.offer(event("fast", "r2"));
        hold.countDown();
        assertTrue(writing.await(5, TimeUnit.SECONDS), "both sessions must write in parallel");
        Thread.sleep(50);
        assertEquals(List.of("r0"), exit.published.stream().map(UserLaneTest::request).toList());
        slowWrite.countDown();
        exit.await(3);
        assertEquals(List.of("r0", "r1", "r2"), exit.published.stream().map(UserLaneTest::request).toList());
    }

    @Test
    void writeFailureHaltsAndNothingOfTheBatchIsPublished() throws Exception {
        UserLane lane = new UserLane(ALICE, sequence, (p, s, events) -> {
            throw new IOException("disk full");
        }, exit);
        lane.offer(event("a", "r0"));
        exit.await(1);
        assertTrue(exit.halted);
        assertEquals(1, exit.failures.size());
        assertTrue(exit.published.isEmpty());
        lane.offer(event("a", "r1"));
        exit.await(2);
        assertEquals(1, exit.failures.size(), "later events are dropped without writing");
    }

    @Test
    void failureInOneOfSeveralSessionsStillFailsTheBatch() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        UserLane lane = new UserLane(ALICE, sequence, (p, s, events) -> {
            if (s.value().equals("first")) {
                try {
                    hold.await();
                } catch (InterruptedException stop) {
                    throw new IOException(stop);
                }
            } else if (s.value().equals("bad")) {
                throw new IOException("disk full");
            }
        }, exit);
        lane.offer(event("first", "r0"));
        Thread.sleep(50);
        lane.offer(event("good", "r1"));
        lane.offer(event("bad", "r2"));
        hold.countDown();
        exit.await(3);
        assertTrue(exit.halted);
        assertEquals(List.of("r0"), exit.published.stream().map(UserLaneTest::request).toList());
    }

    @Test
    void sequenceFailureHaltsWithoutWriting() throws Exception {
        List<EventEnvelope> written = Collections.synchronizedList(new ArrayList<>());
        UserLane lane = new UserLane(ALICE, principal -> {
            throw new IllegalStateException("exceeded");
        }, (p, s, events) -> written.addAll(events), exit);
        lane.offer(event("a", "r0"));
        exit.await(1);
        assertTrue(exit.halted);
        assertTrue(written.isEmpty());
    }

    @Test
    void refusedPublishHaltsAndReleasesTheRest() throws Exception {
        exit.accept = false;
        UserLane lane = new UserLane(ALICE, sequence, (p, s, events) -> { }, exit);
        lane.offer(event("a", "r0"));
        exit.await(1);
        assertTrue(exit.halted);
        assertEquals(1, exit.failures.size());
    }

    /** 线程退出和新事件到达竞争时不能丢事件：每发一个就等它走完，让线程反复退出。 */
    @Test
    void laneExitRacingWithNewEventsLosesNothing() throws Exception {
        UserLane lane = new UserLane(ALICE, sequence, (p, s, events) -> { }, exit);
        int total = 5_000;
        for (int i = 0; i < total; i++) {
            lane.offer(event("a", "r" + i));
            while (exit.published.size() <= i && i % 2 == 0) {
                Thread.onSpinWait();
            }
        }
        exit.await(total);
        for (int i = 0; i < total; i++) {
            assertEquals("r" + i, request(exit.published.get(i)));
        }
    }
}
