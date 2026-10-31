package tantalum.shell.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CallLimiterTest {

    @Test
    void waitingCallTimesOutWhenFull() throws Exception {
        CallLimiter limiter = new CallLimiter(1);
        CallLimiter.Permit held = limiter.acquire(Duration.ofSeconds(1));
        assertEquals(0, limiter.available());
        assertThrows(TimeoutException.class, () -> limiter.acquire(Duration.ofMillis(10)));
        held.close();
    }

    @Test
    void permitReturnsOnCloseAndCloseIsIdempotent() throws Exception {
        CallLimiter limiter = new CallLimiter(2);
        CallLimiter.Permit permit = limiter.acquire(Duration.ofSeconds(1));
        permit.close();
        permit.close();
        assertEquals(2, limiter.available());
    }

    @Test
    void waitingCallCanBeInterrupted() throws Exception {
        CallLimiter limiter = new CallLimiter(1);
        CallLimiter.Permit held = limiter.acquire(Duration.ofSeconds(1));
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                limiter.acquire(Duration.ofMinutes(1));
            } catch (InterruptedException expected) {
                interrupted.set(true);
            } catch (TimeoutException unexpected) {
                throw new AssertionError(unexpected);
            }
            done.countDown();
        });
        Thread.sleep(20);
        waiter.interrupt();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(interrupted.get());
        held.close();
        assertEquals(1, limiter.available());
    }

    @Test
    void limitMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new CallLimiter(0));
    }
}
