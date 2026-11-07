package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tantalum.shell.journal.Sequence;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.Cancel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.TimerFired;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;

class LaneDispatcherTest {

    private final AtomicLong counter = new AtomicLong();
    private final Sequence sequence = principal -> counter.getAndIncrement();
    private final List<EventEnvelope> reached = new CopyOnWriteArrayList<>();

    private static EventEnvelope event(String user, int i) {
        return new EventEnvelope(EventEnvelope.UNASSIGNED, Instant.EPOCH, new Principal(user), new SessionId("s-" + user), null,
                new Cancel(new RequestId("r" + i)));
    }

    /** 定时器：不计入用户上限（需求 4.4）。 */
    private static EventEnvelope timer(String user, int i) {
        return new EventEnvelope(EventEnvelope.UNASSIGNED, Instant.EPOCH, new Principal(user), new SessionId("s-" + user), null,
                new TimerFired(new CommandId("t" + i)));
    }

    private static String name(EventEnvelope envelope) {
        return envelope.principal().value() + ":" + (envelope.event() instanceof Cancel cancel ? cancel.request().value()
                : ((TimerFired) envelope.event()).command().value());
    }

    private List<String> reachedNames() {
        return reached.stream().map(LaneDispatcherTest::name).toList();
    }

    private void awaitReached(int count) throws InterruptedException {
        for (int i = 0; i < 1000 && reached.size() < count; i++) {
            Thread.sleep(5);
        }
        assertEquals(count, reached.size());
    }

    private static void feed(LaneDispatcher dispatcher, EventEnvelope envelope) throws InterruptedException {
        InputRing.Slot slot = new InputRing.Slot();
        slot.envelope = envelope;
        dispatcher.onEvent(slot, 0, true);
    }

    /** 总名额用完后分发线程等待，输入环填满，入口拒绝；放开后事件一个不丢。每个事件来自不同用户，免得先碰到用户上限。 */
    @Test
    void withoutPermitsTheInputRingFillsUpAndNothingIsLost() throws Exception {
        int permits = 4;
        int ringSize = 8;
        LaneDispatcher dispatcher = LoopFixtures.dispatcher(permits, sequence, (p, s, events) -> { }, envelope -> reached.add(envelope));
        InputRing input = LoopFixtures.inputRing(ringSize, dispatcher);
        input.start();
        int accepted = 0;
        for (int i = 0; i < 100 && input.tryPublish(event("u" + i, i)); i++) {
            accepted++;
            Thread.sleep(2);
        }
        assertTrue(accepted < 100, "the ring must refuse once permits and ring are full");
        assertEquals(permits, reached.size(), "no more events than permits may be in flight");
        for (int i = 0; i < accepted; i++) {
            dispatcher.released(reached.get(i));
            Thread.sleep(1);
        }
        awaitReached(accepted);
        input.shutdown(Duration.ZERO);
    }

    @Test
    void awaitIdleWaitsForEveryEventToBeReleased() throws Exception {
        LaneDispatcher dispatcher = LoopFixtures.dispatcher(4, sequence, (p, s, events) -> { }, envelope -> reached.add(envelope));
        feed(dispatcher, event("u", 0));
        awaitReached(1);
        assertFalse(dispatcher.awaitIdle(Duration.ofMillis(20)));
        dispatcher.released(reached.getFirst());
        assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(1)));
    }

    /** ops-hardening-2 需求 4.1、4.3：A 到上限后 A 的下一个事件停住、不占总名额，B 的照常通过；A 归还一个，停住的那个按顺序进去。 */
    @Test
    void aUserAtTheirLimitWaitsWhileOthersPass() throws Exception {
        LaneDispatcher dispatcher = LoopFixtures.dispatcher(8, 2, sequence, (p, s, events) -> { }, envelope -> reached.add(envelope));
        feed(dispatcher, event("a", 1));
        feed(dispatcher, event("a", 2));
        feed(dispatcher, event("a", 3));
        feed(dispatcher, event("a", 4));
        feed(dispatcher, event("b", 1));
        awaitReached(3);
        // a 和 b 各自的通道是两个线程，a、b 之间的先后不定；a 自己的顺序不变
        assertEquals(List.of("a:r1", "a:r2", "b:r1"), reachedNames().stream().sorted().toList());
        assertFalse(dispatcher.awaitIdle(Duration.ofMillis(20)), "parked events are not idle");

        dispatcher.released(reached.stream().filter(e -> name(e).equals("a:r1")).findFirst().orElseThrow());
        awaitReached(4);
        assertEquals("a:r3", name(reached.get(3)));
        dispatcher.released(reached.stream().filter(e -> name(e).equals("a:r2")).findFirst().orElseThrow());
        awaitReached(5);
        assertEquals("a:r4", name(reached.get(4)));
        for (EventEnvelope left : reached.stream().filter(e -> !name(e).equals("a:r1") && !name(e).equals("a:r2")).toList()) {
            dispatcher.released(left);
        }
        assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(1)));
    }

    /** 需求 4.4：定时器不计入上限、直接通过；但 A 有事件停住时，它排在后面，保持顺序。 */
    @Test
    void timersIgnoreTheLimitButKeepTheUsersOrder() throws Exception {
        LaneDispatcher dispatcher = LoopFixtures.dispatcher(8, 2, sequence, (p, s, events) -> { }, envelope -> reached.add(envelope));
        feed(dispatcher, event("a", 1));
        feed(dispatcher, event("a", 2));
        feed(dispatcher, timer("a", 1));
        awaitReached(3);
        assertEquals(List.of("a:r1", "a:r2", "a:t1"), reachedNames());
        feed(dispatcher, event("a", 3));
        feed(dispatcher, timer("a", 2));
        Thread.sleep(20);
        assertEquals(3, reached.size(), "the timer waits behind the parked event");
        dispatcher.released(reached.get(0));
        awaitReached(5);
        assertEquals(List.of("a:r1", "a:r2", "a:t1", "a:r3", "a:t2"), reachedNames());
    }

    @Test
    void writeFailureHaltsAndReturnsThePermits() throws Exception {
        LaneDispatcher dispatcher = LoopFixtures.dispatcher(4, sequence, (p, s, events) -> {
            throw new IOException("disk full");
        }, envelope -> reached.add(envelope));
        feed(dispatcher, event("u", 0));
        awaitReached(1);
        EventEnvelope processed = reached.getFirst();
        List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
        dispatcher.release(processed, new StepRecord(processed.seq(), null, List.of(), List.of(), null, null, List.of(), List.of()),
                () -> log.add("released"), () -> {
                    log.add("done");
                    dispatcher.released(processed);
                });
        for (int i = 0; i < 1000 && !dispatcher.halted(); i++) {
            Thread.sleep(5);
        }
        assertTrue(dispatcher.halted());
        assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(1)));
        assertEquals(List.of("done"), log, "the step's commands are not released");
        reached.clear();
        // 停止之后分发线程不再等名额，也不再放行。
        feed(dispatcher, event("u", 1));
        assertTrue(reached.isEmpty());
    }

    /** review-fixes 需求 2.6：停机后停在用户上限外的事件被丢弃、名额归还，awaitIdle 不再空转到超时。 */
    @Test
    void haltDiscardsTheEventsParkedAtTheUserLimit() throws Exception {
        LaneDispatcher dispatcher = LoopFixtures.dispatcher(8, 1, sequence, (p, s, events) -> {
            throw new IOException("disk full");
        }, envelope -> reached.add(envelope));
        feed(dispatcher, event("u", 0));
        feed(dispatcher, event("u", 1));
        feed(dispatcher, event("u", 2));
        awaitReached(1);
        EventEnvelope processed = reached.getFirst();
        dispatcher.release(processed, new StepRecord(processed.seq(), null, List.of(), List.of(), null, null, List.of(), List.of()),
                () -> { }, () -> dispatcher.released(processed));
        for (int i = 0; i < 1000 && !dispatcher.halted(); i++) {
            Thread.sleep(5);
        }
        assertTrue(dispatcher.halted());
        assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(2)), "the two parked events were discarded, nothing is pending");
        assertEquals(1, reached.size(), "the parked events never reached the lane");
    }
}
