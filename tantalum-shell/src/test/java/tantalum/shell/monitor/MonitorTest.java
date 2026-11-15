package tantalum.shell.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/** live-monitor 需求 1.4–1.6、2.3、5.1：没人看时什么都不排，满了丢最旧的并报数，按用户隔离；开销。 */
class MonitorTest {

    private static final Principal YI = new Principal("yi");
    private static final Principal BOB = new Principal("bob");
    private static final SessionId S1 = new SessionId("s1");

    @Test
    void nothingIsQueuedWithoutASubscriberAndMessagesArePerUser() throws Exception {
        Monitor monitor = new Monitor(Clock.systemUTC());
        assertFalse(monitor.watching(YI));
        monitor.at(YI, Stage.INTAKE, S1, null, null, null, "UserPrompt", null);
        try (Monitor.Subscription yi = monitor.subscribe(YI)) {
            assertTrue(monitor.watching(YI));
            assertFalse(monitor.watching(BOB));
            monitor.at(BOB, Stage.INTAKE, S1, null, null, null, "UserPrompt", null);
            monitor.at(YI, Stage.LANE, S1, null, 7L, null, "UserPrompt", "seq 7");
            MonitorMessage message = yi.poll(1, TimeUnit.SECONDS);
            assertEquals(Stage.LANE, message.stage());
            assertEquals("s1", message.session());
            assertEquals(7L, message.seq());
            assertEquals(0, message.dropped());
            assertNull(yi.poll(10, TimeUnit.MILLISECONDS), "the earlier message and bob's are not there");
        }
        assertFalse(monitor.watching(YI));
        assertEquals(0, monitor.subscribers());
    }

    @Test
    void closingWakesAWaitingPollAtOnce() throws Exception {
        Monitor monitor = new Monitor(Clock.systemUTC());
        Monitor.Subscription yi = monitor.subscribe(YI);
        long[] waited = new long[1];
        Thread poller = Thread.ofPlatform().start(() -> {
            long start = System.nanoTime();
            try {
                assertNull(yi.poll(30, TimeUnit.SECONDS));
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
            waited[0] = (System.nanoTime() - start) / 1_000_000;
        });
        Thread.sleep(100);
        yi.close();
        poller.join(5_000);
        assertFalse(poller.isAlive());
        assertTrue(waited[0] < 2_000, "the poll waited " + waited[0] + " ms after close; shutdown would wait for it");
        monitor.closeAll();
    }

    @Test
    void aFullQueueDropsTheOldestAndReportsHowMany() throws Exception {
        Monitor monitor = new Monitor(Clock.systemUTC());
        try (Monitor.Subscription yi = monitor.subscribe(YI)) {
            for (int i = 0; i < Monitor.QUEUE + 5; i++) {
                monitor.at(YI, Stage.KERNEL, S1, null, (long) i, null, "E", null);
            }
            MonitorMessage first = yi.poll(1, TimeUnit.SECONDS);
            assertEquals(5L, first.seq(), "the five oldest were dropped");
            assertEquals(5, first.dropped());
            assertEquals(0, yi.poll(1, TimeUnit.SECONDS).dropped());
        }
    }

    @Test
    void theProbeIsCheapWithoutASubscriberAndStaysCheapWithOne() throws Exception {
        Monitor monitor = new Monitor(Clock.systemUTC());
        SessionId session = S1;
        long sink = 0;
        for (int i = 0; i < 2_000_000; i++) {
            if (monitor.watching(YI)) {
                monitor.at(YI, Stage.INPUT, session, null, null, null, "E", null);
            }
            sink += i;
        }
        int calls = 10_000_000;
        long start = System.nanoTime();
        for (int i = 0; i < calls; i++) {
            if (monitor.watching(YI)) {
                monitor.at(YI, Stage.INPUT, session, null, null, null, "E", null);
            }
        }
        double idle = (System.nanoTime() - start) / (double) calls;

        AtomicBoolean done = new AtomicBoolean();
        try (Monitor.Subscription yi = monitor.subscribe(YI)) {
            Thread drain = Thread.ofPlatform().start(() -> {
                while (!done.get()) {
                    try {
                        yi.poll(10, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException stop) {
                        return;
                    }
                }
            });
            int watched = 1_000_000;
            for (int i = 0; i < 200_000; i++) {
                monitor.at(YI, Stage.INPUT, session, null, (long) i, null, "E", "detail");
            }
            start = System.nanoTime();
            for (int i = 0; i < watched; i++) {
                if (monitor.watching(YI)) {
                    monitor.at(YI, Stage.INPUT, session, null, (long) i, null, "E", "detail");
                }
            }
            double busy = (System.nanoTime() - start) / (double) watched;
            done.set(true);
            drain.join();
            System.out.printf("monitor probe: %.1f ns idle, %.1f ns with a subscriber (sink %d)%n", idle, busy, sink);
            assertTrue(idle < 50, "idle probe took " + idle + " ns");
            assertTrue(busy < 2_000, "watched probe took " + busy + " ns");
        }
    }
}
