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

    @Test
    void inputRingRunsTheSecondHandlerAfterTheFirst() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        InputRing ring = new InputRing(8,
                (slot, seq, end) -> order.add("first"),
                (slot, seq, end) -> {
                    order.add("second");
                    done.countDown();
                });
        ring.start();
        assertTrue(ring.tryPublish(envelope()));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("first", "second"), order);
        ring.shutdown();
    }

    @Test
    void fullInputRingReturnsFalseWithoutWaiting() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        InputRing ring = new InputRing(4, (slot, seq, end) -> release.await(), (slot, seq, end) -> { });
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
        ring.shutdown();
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
        ring.shutdown();
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
        ring.shutdown();
    }
}
