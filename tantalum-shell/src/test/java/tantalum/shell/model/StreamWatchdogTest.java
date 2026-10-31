package tantalum.shell.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StreamWatchdogTest {

    @Test
    void firesWhenTheFirstChunkNeverArrives() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        try (StreamWatchdog watchdog = new StreamWatchdog(Duration.ofMillis(20), Duration.ofHours(1), fired::countDown)) {
            assertTrue(fired.await(5, TimeUnit.SECONDS));
            assertTrue(watchdog.expired());
        }
    }

    @Test
    void firesWhenChunksStop() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        try (StreamWatchdog watchdog = new StreamWatchdog(Duration.ofHours(1), Duration.ofMillis(20), fired::countDown)) {
            watchdog.activity();
            assertTrue(fired.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void activityKeepsItAlive() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        try (StreamWatchdog watchdog = new StreamWatchdog(Duration.ofMillis(200), Duration.ofMillis(200), fired::countDown)) {
            for (int i = 0; i < 10; i++) {
                watchdog.activity();
                Thread.sleep(40);
            }
            assertFalse(watchdog.expired());
        }
        assertFalse(fired.await(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void closeStopsIt() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        new StreamWatchdog(Duration.ofMillis(50), Duration.ofMillis(50), fired::countDown).close();
        assertFalse(fired.await(200, TimeUnit.MILLISECONDS));
    }
}
