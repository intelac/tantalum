package tantalum.shell.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private Instant now = Instant.parse("2026-10-31T00:00:00Z");

    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    private LoginThrottle throttle() {
        return new LoginThrottle(3, 10, Duration.ofMinutes(15), clock);
    }

    @Test
    void accountIsLimitedAfterRepeatedFailures() {
        LoginThrottle throttle = throttle();
        for (int i = 0; i < 3; i++) {
            assertTrue(throttle.allowed("alice", "1.1.1.1"));
            throttle.failed("alice", "1.1.1.1");
        }
        assertFalse(throttle.allowed("alice", "2.2.2.2"));
        assertTrue(throttle.allowed("bob", "2.2.2.2"));
    }

    @Test
    void sourceIsLimitedAcrossAccounts() {
        LoginThrottle throttle = throttle();
        for (int i = 0; i < 10; i++) {
            throttle.failed("user" + i, "1.1.1.1");
        }
        assertFalse(throttle.allowed("someone", "1.1.1.1"));
        assertTrue(throttle.allowed("someone", "2.2.2.2"));
    }

    @Test
    void successClearsTheAccountCount() {
        LoginThrottle throttle = throttle();
        throttle.failed("alice", "1.1.1.1");
        throttle.failed("alice", "1.1.1.1");
        throttle.succeeded("alice");
        throttle.failed("alice", "1.1.1.1");
        assertTrue(throttle.allowed("alice", "1.1.1.1"));
    }

    @Test
    void limitExpires() {
        LoginThrottle throttle = throttle();
        for (int i = 0; i < 3; i++) {
            throttle.failed("alice", "1.1.1.1");
        }
        now = now.plus(Duration.ofMinutes(16));
        assertTrue(throttle.allowed("alice", "1.1.1.1"));
    }

    @Test
    void manyDistinctNamesDoNotGrowWithoutBound() {
        LoginThrottle throttle = throttle();
        for (int i = 0; i < 50_000; i++) {
            throttle.failed("user" + i, "ip" + i);
        }
        assertTrue(throttle.trackedKeys() <= LoginThrottle.MAX_KEYS);
    }
}
