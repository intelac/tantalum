package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
        @Override public void discarded(List<EventEnvelope> events) { discarded.addAndGet(events.size()); }

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

    /** 一批里几个会话的步骤并行写，全部写完才放行：不会有一步比同一批更早的步骤先放行命令。 */
    @Test
    void nothingIsReleasedUntilEverySessionOfTheBatchIsWritten() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        CountDownLatch slowWrite = new CountDownLatch(1);
        CountDownLatch writing = new CountDownLatch(2);
        CountDownLatch hold = new CountDownLatch(1);
        // 先用一步占住通道，让后两步进同一批。
        UserLane held = new UserLane(ALICE, sequence, (p, s, lines) -> {
            if (s.value().equals("first")) {
                await(hold);
                return;
            }
            writing.countDown();
            if (s.value().equals("slow")) {
                await(slowWrite);
            }
        }, exit, LoopFixtures.NO_LOADER, new Residency());
        held.offer(step("first", 0, log));
        Thread.sleep(50);
        held.offer(step("slow", 1, log));
        held.offer(step("fast", 2, log));
        hold.countDown();
        assertTrue(writing.await(5, TimeUnit.SECONDS), "both sessions must write in parallel");
        Thread.sleep(50);
        assertEquals(List.of("released 0", "done 0"), log);
        slowWrite.countDown();
        for (int i = 0; i < 1000 && log.size() < 6; i++) {
            Thread.sleep(5);
        }
        assertEquals(List.of("released 0", "done 0", "released 1", "done 1", "released 2", "done 2"), log);
    }

    /** 写失败就停下：同一批的事件不再交给内核，步骤只归还名额。 */
    @Test
    void writeFailureHaltsAndNothingOfTheBatchGoesOn() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        CountDownLatch hold = new CountDownLatch(1);
        UserLane lane = new UserLane(ALICE, sequence, (p, s, lines) -> {
            if (s.value().equals("first")) {
                await(hold);
                return;
            }
            throw new IOException("disk full");
        }, exit, LoopFixtures.NO_LOADER, new Residency());
        lane.offer(step("first", 0, log));
        Thread.sleep(50);
        lane.offer(step("a", 1, log));
        lane.offer(event("b", "r1"));
        hold.countDown();
        exit.await(1);
        assertTrue(exit.halted);
        assertEquals(1, exit.failures.size());
        assertTrue(exit.published.isEmpty());
        assertEquals(List.of("released 0", "done 0", "done 1"), log);
        lane.offer(event("a", "r2"));
        exit.await(2);
        assertEquals(1, exit.failures.size(), "later events are dropped without writing");
    }

    @Test
    void failureInOneOfSeveralSessionsStillFailsTheBatch() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        CountDownLatch hold = new CountDownLatch(1);
        UserLane lane = new UserLane(ALICE, sequence, (p, s, lines) -> {
            if (s.value().equals("first")) {
                await(hold);
            } else if (s.value().equals("bad")) {
                throw new IOException("disk full");
            }
        }, exit, LoopFixtures.NO_LOADER, new Residency());
        lane.offer(step("first", 0, log));
        Thread.sleep(50);
        lane.offer(step("good", 1, log));
        lane.offer(step("bad", 2, log));
        hold.countDown();
        for (int i = 0; i < 1000 && log.size() < 4; i++) {
            Thread.sleep(5);
        }
        assertTrue(exit.halted);
        assertEquals(List.of("released 0", "done 0", "done 1", "done 2"), log, "the good session's step is not released either");
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            latch.await();
        } catch (InterruptedException stop) {
            throw new IOException(stop);
        }
    }

    @Test
    void sequenceFailureHaltsWithoutWriting() throws Exception {
        List<EventEnvelope> written = Collections.synchronizedList(new ArrayList<>());
        UserLane lane = new UserLane(ALICE, principal -> {
            throw new IllegalStateException("exceeded");
        }, (p, s, lines) -> lines.forEach(line -> written.add(line.envelope())), exit, LoopFixtures.NO_LOADER, new Residency());
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

    /** one-line-journal 需求 1.2：这一行落盘之前不放行命令和推送。 */
    @Test
    void aStepIsReleasedOnlyAfterItsResultIsWritten() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch durable = new CountDownLatch(1);
        UserLane lane = new UserLane(ALICE, sequence, (p, s, lines) -> {
            log.add("write " + lines.stream().map(Line::seq).toList());
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
        assertEquals(List.of("write [7]"), log, "nothing is released while the write is in progress");

        durable.countDown();
        for (int i = 0; i < 1000 && log.size() < 3; i++) {
            Thread.sleep(5);
        }
        assertEquals(List.of("write [7]", "released 7", "done 7"), log);
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

    /** one-line-journal 需求 1.3：事件交给内核之前不写文件；只有处理完的步骤按顺序写。 */
    @Test
    void onlyProcessedStepsAreWrittenInArrivalOrder() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        List<Long> written = new CopyOnWriteArrayList<>();
        CountDownLatch hold = new CountDownLatch(1);
        UserLane lane = new UserLane(ALICE, sequence, (p, s, lines) -> {
            if (s.value().equals("first")) {
                await(hold);
                return;
            }
            lines.forEach(line -> written.add(line.seq()));
        }, exit, LoopFixtures.NO_LOADER, new Residency());
        lane.offer(step("first", 10, log));
        Thread.sleep(20);
        lane.offer(step("a", 11, log));
        lane.offer(event("a", "steer"));
        lane.offer(step("a", 12, log));
        hold.countDown();
        exit.await(1);
        for (int i = 0; i < 1000 && log.size() < 6; i++) {
            Thread.sleep(5);
        }
        assertEquals(List.of(11L, 12L), written, "the steer event is published, not written");
        assertEquals(List.of("steer"), exit.published.stream().map(UserLaneTest::request).toList());
        assertEquals(List.of("released 10", "done 10", "released 11", "done 11", "released 12", "done 12"), log);
    }

    /** ops-hardening-2 需求 5.2：第一次载入失败后拒绝；过了重试时间，下一个事件重新载入，成功就正常。 */
    @Test
    void aFailedLoadIsRetriedAfterTheRetryTime() throws Exception {
        long[] now = {0};
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now[0]); }
        };
        AtomicInteger loads = new AtomicInteger();
        UserLane.Loader loader = LoopFixtures.loader((principal, session, beforeSeq) -> {
            if (loads.incrementAndGet() == 1) {
                throw new IllegalStateException("index locked");
            }
            return Optional.empty();
        });
        List<String> seen = new CopyOnWriteArrayList<>();
        UserLane.Exit capturing = new UserLane.Exit() {
            @Override public boolean halted() { return false; }
            @Override public boolean publish(EventEnvelope envelope, PrincipalState usage, CleanroomRing.Load load) {
                seen.add(load == null ? "loaded" : ((CleanroomRing.Failed) load).reason());
                return true;
            }
            @Override public void failed(Throwable failure) { throw new AssertionError(failure); }
            @Override public void discarded(List<EventEnvelope> events) { throw new AssertionError("discarded " + events.size()); }
        };
        UserLane lane = new UserLane(ALICE, sequence, (p, s, events) -> { }, capturing, loader, new Residency(clock, Duration.ofMinutes(1)));
        lane.offer(event("x", "r1"));
        awaitSize(seen, 1);
        now[0] = 30_000;
        lane.offer(event("x", "r2"));
        awaitSize(seen, 2);
        assertEquals(List.of("java.lang.IllegalStateException: index locked", "java.lang.IllegalStateException: index locked"), seen);
        assertEquals(1, loads.get(), "within the retry time nothing is loaded again");
        now[0] = 60_000;
        lane.offer(event("x", "r3"));
        awaitSize(seen, 3);
        assertEquals("loaded", seen.get(2));
        assertEquals(2, loads.get());
    }

    private static void awaitSize(List<?> list, int size) throws InterruptedException {
        for (int i = 0; i < 1000 && list.size() < size; i++) {
            Thread.sleep(5);
        }
        assertEquals(size, list.size());
    }
}
