package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.Command;
import tantalum.value.event.Cancel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

class RingTest {

    private static final RunRef RUN = new RunRef(new Principal("p"), new SessionId("s"), new RunId("r"));

    private static EventEnvelope envelope() {
        return new EventEnvelope(EventEnvelope.UNASSIGNED, Instant.EPOCH, RUN.principal(), RUN.session(), null,
                new Cancel(new RequestId("r")));
    }

    private static Command command(int i) {
        return new AssembleContext(new CommandId("c" + i), RUN);
    }

    /** 消费者抛出后环必须继续转并留下信号；Disruptor 默认处理器会把线程杀掉而无人知道。 */
    @Test
    void consumerExceptionMarksTheRingBrokenButKeepsConsuming() throws Exception {
        CountDownLatch second = new CountDownLatch(2);
        InputRing ring = LoopFixtures.inputRing(8, (slot, seq, end) -> {
            second.countDown();
            if (seq == 0) {
                throw new IllegalStateException("writer bug");
            }
        });
        ring.start();
        assertFalse(ring.broken());
        assertTrue(ring.tryPublish(envelope()));
        assertTrue(ring.tryPublish(envelope()));
        assertTrue(second.await(5, TimeUnit.SECONDS));
        assertTrue(ring.broken());
        ring.shutdown(Duration.ZERO);
    }

    @Test
    void outputRingConsumerExceptionIsRecorded() throws Exception {
        CountDownLatch delivered = new CountDownLatch(2);
        OutputRing ring = new OutputRing(8, (slot, seq, end) -> {
            delivered.countDown();
            if (seq == 0) {
                throw new IllegalStateException("dispatcher bug");
            }
        });
        ring.start();
        assertTrue(ring.tryPublish(command(0)));
        assertTrue(ring.tryPublish(command(1)));
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertTrue(ring.broken());
        ring.shutdown(Duration.ZERO);
    }

    /** 正常关闭要把已发布的事件处理完：客户端已经收到 200 的提交不能丢。 */
    @Test
    void gracefulShutdownDrainsPublishedEvents() throws Exception {
        List<Long> seen = new CopyOnWriteArrayList<>();
        CountDownLatch running = new CountDownLatch(1);
        InputRing ring = LoopFixtures.inputRing(8, (slot, seq, end) -> {
            running.countDown();
            Thread.sleep(20);
            seen.add(seq);
        });
        ring.start();
        for (int i = 0; i < 5; i++) {
            assertTrue(ring.tryPublish(envelope()));
        }
        // Disruptor 只等已在运行的消费者；服务不会在启动的同一瞬间关闭，这里等它们跑起来。
        assertTrue(running.await(5, TimeUnit.SECONDS));
        ring.shutdown(Duration.ofSeconds(5));
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), seen);
    }

    @Test
    void fullInputRingReturnsFalseWithoutWaiting() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        InputRing ring = LoopFixtures.inputRing(4, (slot, seq, end) -> release.await());
        ring.start();
        boolean sawFull = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 16; i++) {
                if (!ring.tryPublish(envelope())) {
                    return true;
                }
            }
            return false;
        });
        assertTrue(sawFull);
        assertEquals(0, ring.remainingCapacity());
        release.countDown();
        ring.shutdown(Duration.ZERO);
    }

    @Test
    void fullOutputRingReturnsFalseWithoutWaiting() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        OutputRing ring = new OutputRing(4, (slot, seq, end) -> release.await());
        ring.start();
        boolean sawFull = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 16; i++) {
                if (!ring.tryPublish(command(i))) {
                    return true;
                }
            }
            return false;
        });
        assertTrue(sawFull);
        release.countDown();
        ring.shutdown(Duration.ZERO);
    }

    @Test
    void outputRingDeliversCommandsInOrder() throws Exception {
        List<Command> received = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(3);
        OutputRing ring = new OutputRing(4, (slot, seq, end) -> {
            received.add(slot.command);
            done.countDown();
        });
        ring.start();
        for (int i = 0; i < 3; i++) {
            assertTrue(ring.tryPublish(command(i)));
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(command(0), command(1), command(2)), received);
        assertFalse(received.isEmpty());
        ring.shutdown(Duration.ZERO);
    }
}
