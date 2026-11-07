package tantalum.shell.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** review-fixes 需求 10.1：看门狗自己持有流，过期后才发布的流也会被关掉。 */
class StreamWatchdogTest {

    @Test
    void aStreamPublishedAfterExpiryIsClosedAtOnce() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        try (StreamWatchdog watchdog = new StreamWatchdog(Duration.ofMillis(20), Duration.ofMillis(20))) {
            for (int i = 0; i < 200 && !watchdog.expired(); i++) {
                Thread.sleep(5);
            }
            assertTrue(watchdog.expired());
            watchdog.watch(closed::incrementAndGet);
            assertEquals(1, closed.get(), "the late stream is closed by the publisher, not left open");
        }
    }

    @Test
    void activityKeepsTheStreamOpenAndAbandonClosesItOnce() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        try (StreamWatchdog watchdog = new StreamWatchdog(Duration.ofMillis(200), Duration.ofMillis(200))) {
            watchdog.watch(closed::incrementAndGet);
            for (int i = 0; i < 10; i++) {
                Thread.sleep(30);
                watchdog.activity();
            }
            assertFalse(watchdog.expired());
            assertEquals(0, closed.get());
            watchdog.abandon();
            watchdog.abandon();
            assertEquals(1, closed.get());
        }
    }

    @Test
    void expiryClosesTheStreamItHolds() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        try (StreamWatchdog watchdog = new StreamWatchdog(Duration.ofMillis(20), Duration.ofSeconds(5))) {
            watchdog.watch(closed::incrementAndGet);
            for (int i = 0; i < 200 && closed.get() == 0; i++) {
                Thread.sleep(5);
            }
            assertTrue(watchdog.expired());
            assertEquals(1, closed.get());
        }
    }
}
