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
import tantalum.value.event.Cancel;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.EventEnvelope;
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

    private void awaitReached(int count) throws InterruptedException {
        for (int i = 0; i < 1000 && reached.size() < count; i++) {
            Thread.sleep(5);
        }
        assertEquals(count, reached.size());
    }

    /** 名额用完后分发线程等待，输入环填满，入口拒绝；放开后事件一个不丢。 */
    @Test
    void withoutPermitsTheInputRingFillsUpAndNothingIsLost() throws Exception {
        int permits = 4;
        int ringSize = 8;
        LaneDispatcher dispatcher = LoopFixtures.dispatcher(permits, sequence, (p, s, events) -> { }, envelope -> reached.add(envelope));
        InputRing input = LoopFixtures.inputRing(ringSize, dispatcher);
        input.start();
        int accepted = 0;
        for (int i = 0; i < 100 && input.tryPublish(event("u", i)); i++) {
            accepted++;
            Thread.sleep(2);
        }
        assertTrue(accepted < 100, "the ring must refuse once permits and ring are full");
        assertEquals(permits, reached.size(), "no more events than permits may be in flight");
        for (int i = 0; i < accepted; i++) {
            dispatcher.released();
            Thread.sleep(1);
        }
        awaitReached(accepted);
        input.shutdown(Duration.ZERO);
    }

    @Test
    void awaitIdleWaitsForEveryEventToBeReleased() throws Exception {
        LaneDispatcher dispatcher = LoopFixtures.dispatcher(4, sequence, (p, s, events) -> { }, envelope -> reached.add(envelope));
        InputRing.Slot slot = new InputRing.Slot();
        slot.envelope = event("u", 0);
        dispatcher.onEvent(slot, 0, true);
        awaitReached(1);
        assertFalse(dispatcher.awaitIdle(Duration.ofMillis(20)));
        dispatcher.released();
        assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(1)));
    }

    @Test
    void writeFailureHaltsAndReturnsThePermits() throws Exception {
        LaneDispatcher dispatcher = LoopFixtures.dispatcher(4, sequence, (p, s, events) -> {
            throw new IOException("disk full");
        }, envelope -> reached.add(envelope));
        InputRing.Slot slot = new InputRing.Slot();
        slot.envelope = event("u", 0);
        dispatcher.onEvent(slot, 0, true);
        awaitReached(1);
        EventEnvelope processed = reached.getFirst();
        List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
        dispatcher.release(processed, new StepRecord(processed.seq(), null, List.of(), List.of(), null, null, List.of(), List.of()),
                () -> log.add("released"), () -> {
                    log.add("done");
                    dispatcher.released();
                });
        for (int i = 0; i < 1000 && !dispatcher.halted(); i++) {
            Thread.sleep(5);
        }
        assertTrue(dispatcher.halted());
        assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(1)));
        assertEquals(List.of("done"), log, "the step's commands are not released");
        reached.clear();
        // 停止之后分发线程不再等名额，也不再放行。
        InputRing.Slot later = new InputRing.Slot();
        later.envelope = event("u", 1);
        dispatcher.onEvent(later, 1, true);
        assertTrue(reached.isEmpty());
    }
}
