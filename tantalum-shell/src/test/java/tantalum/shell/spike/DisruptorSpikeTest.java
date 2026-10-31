package tantalum.shell.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DisruptorSpikeTest {

    static final class Slot {
        long value;
    }

    private static Disruptor<Slot> disruptor(int size) {
        return new Disruptor<>(Slot::new, size, DaemonThreadFactory.INSTANCE,
                ProducerType.MULTI, new BlockingWaitStrategy());
    }

    @Test
    void tryPublishReturnsFalseWhenFullWithoutBlocking() throws Exception {
        Disruptor<Slot> disruptor = disruptor(8);
        CountDownLatch release = new CountDownLatch(1);
        EventHandler<Slot> stuck = (slot, seq, endOfBatch) -> release.await();
        disruptor.handleEventsWith(stuck);
        disruptor.start();

        boolean sawFull = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 64; i++) {
                if (!disruptor.getRingBuffer().tryPublishEvent((slot, seq) -> slot.value = seq)) {
                    return true;
                }
            }
            return false;
        });

        assertTrue(sawFull);
        release.countDown();
        disruptor.shutdown(5, TimeUnit.SECONDS);
    }

    @Test
    void secondConsumerSeesOnlyEventsCommittedAtEndOfBatch() throws Exception {
        int total = 2_000;
        Disruptor<Slot> disruptor = disruptor(64);
        AtomicLong committed = new AtomicLong(-1);
        AtomicLong lastSeen = new AtomicLong(-1);
        AtomicInteger uncommittedSeen = new AtomicInteger();
        AtomicInteger outOfOrder = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(total);

        EventHandler<Slot> journal = (slot, seq, endOfBatch) -> {
            if (endOfBatch) {
                Thread.sleep(1);
                committed.set(seq);
            }
        };
        EventHandler<Slot> cleanroom = (slot, seq, endOfBatch) -> {
            if (seq > committed.get()) {
                uncommittedSeen.incrementAndGet();
            }
            if (seq != lastSeen.getAndSet(seq) + 1) {
                outOfOrder.incrementAndGet();
            }
            done.countDown();
        };
        disruptor.handleEventsWith(journal).then(cleanroom);
        disruptor.start();

        for (int i = 0; i < total; ) {
            if (disruptor.getRingBuffer().tryPublishEvent((slot, seq) -> slot.value = seq)) {
                i++;
            } else {
                Thread.onSpinWait();
            }
        }

        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertEquals(0, uncommittedSeen.get());
        assertEquals(0, outOfOrder.get());
        assertFalse(committed.get() < total - 1);
        disruptor.shutdown(5, TimeUnit.SECONDS);
    }
}
