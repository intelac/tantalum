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
import tantalum.shell.journal.Line;
import tantalum.shell.journal.Sequence;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.Cancel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.state.PrincipalState;

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
        @Override public boolean publish(EventEnvelope envelope, PrincipalState usage, CleanroomRing.Load load) {
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
        }, exit, LoopFixtures.NO_LOADER, new Residency());
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
        }, exit, LoopFixtures.NO_LOADER, new Residency());
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
        }, exit, LoopFixtures.NO_LOADER, new Residency());
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
        }, exit, LoopFixtures.NO_LOADER, new Residency());
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
        }, (p, s, lines) -> lines.forEach(line -> written.add(((Line.Event) line).envelope())), exit, LoopFixtures.NO_LOADER, new Residency());
        lane.offer(event("a", "r0"));
        exit.await(1);
        assertTrue(exit.halted);
        assertTrue(written.isEmpty());
    }

    @Test
    void refusedPublishHaltsAndReleasesTheRest() throws Exception {
        exit.accept = false;
        UserLane lane = new UserLane(ALICE, sequence, (p, s, events) -> { }, exit, LoopFixtures.NO_LOADER, new Residency());
        lane.offer(event("a", "r0"));
        exit.await(1);
        assertTrue(exit.halted);
        assertEquals(1, exit.failures.size());
    }

    /** 线程退出和新事件到达竞争时不能丢事件：每发一个就等它走完，让线程反复退出。 */
    @Test
    void laneExitRacingWithNewEventsLosesNothing() throws Exception {
        UserLane lane = new UserLane(ALICE, sequence, (p, s, events) -> { }, exit, LoopFixtures.NO_LOADER, new Residency());
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

    private static UserLane.Released step(String session, long seq, List<String> log) {
        EventEnvelope envelope = event(session, "r" + seq).withSeq(seq);
        StepRecord record = new StepRecord(seq, null, List.of(), List.of(), null, null, List.of(), List.of());
        return new UserLane.Released(envelope, record, () -> log.add("released " + seq), () -> log.add("done " + seq));
    }

    /** record-results 需求 2.1：结果行落盘之前不放行命令和推送。 */
    @Test
    void aStepIsReleasedOnlyAfterItsResultIsWritten() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch durable = new CountDownLatch(1);
        UserLane lane = new UserLane(ALICE, sequence, (p, s, lines) -> {
            log.add("write " + lines.stream().map(line -> line.getClass().getSimpleName() + " " + line.seq()).toList());
            writing.countDown();
            try {
                durable.await();
            } catch (InterruptedException stop) {
                throw new IOException(stop);
            }
        }, exit, LoopFixtures.NO_LOADER, new Residency());

        lane.offer(step("a", 7, log));
        assertTrue(writing.await(5, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertEquals(List.of("write [Result 7]"), log, "nothing is released while the write is in progress");

        durable.countDown();
        for (int i = 0; i < 1000 && log.size() < 3; i++) {
            Thread.sleep(5);
        }
        assertEquals(List.of("write [Result 7]", "released 7", "done 7"), log);
    }

    /** 需求 2.2：写不进去就不放行，只归还名额；进程照事件写入失败的规则停下。 */
    @Test
    void aFailedWriteReleasesNothing() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        UserLane lane = new UserLane(ALICE, sequence, (p, s, lines) -> {
            throw new IOException("disk full");
        }, exit, LoopFixtures.NO_LOADER, new Residency());

        lane.offer(step("a", 3, log));
        for (int i = 0; i < 1000 && log.isEmpty(); i++) {
            Thread.sleep(5);
        }
        assertEquals(List.of("done 3"), log);
        assertEquals(1, exit.failures.size());
    }

    /** 事件和结果按到达顺序进同一次写入。 */
    @Test
    void eventsAndResultsOfOneSessionAreWrittenInArrivalOrder() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        List<String> written = new CopyOnWriteArrayList<>();
        CountDownLatch hold = new CountDownLatch(1);
        UserLane lane = new UserLane(ALICE, sequence, (p, s, lines) -> {
            if (s.value().equals("first")) {
                try {
                    hold.await();
                } catch (InterruptedException stop) {
                    throw new IOException(stop);
                }
                return;
            }
            lines.forEach(line -> written.add(line.getClass().getSimpleName()));
        }, exit, LoopFixtures.NO_LOADER, new Residency());
        lane.offer(event("first", "hold"));
        Thread.sleep(20);
        lane.offer(step("a", 0, log));
        lane.offer(event("a", "steer"));
        lane.offer(step("a", 1, log));
        hold.countDown();
        exit.await(2);
        for (int i = 0; i < 1000 && log.size() < 4; i++) {
            Thread.sleep(5);
        }
        assertEquals(List.of("Result", "Event", "Result"), written);
        assertEquals(List.of("released 0", "done 0", "released 1", "done 1"), log);
    }
}
