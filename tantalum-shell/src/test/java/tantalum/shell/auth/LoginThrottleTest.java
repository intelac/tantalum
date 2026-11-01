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
            assertTrue(throttle.tryBegin("alice", "1.1.1.1"));
        }
        assertFalse(throttle.tryBegin("alice", "2.2.2.2"));
        assertTrue(throttle.tryBegin("bob", "2.2.2.2"));
    }

    /** 并发猜密码：一批请求同时开始，还没有任何一次“失败”被记录。预占计数让第 4 次就被拒，不等 Argon2 校验结束。 */
    @Test
    void attemptsAreCountedWhenTheyStartNotWhenTheyFail() {
        LoginThrottle throttle = throttle();
        assertTrue(throttle.tryBegin("alice", "1.1.1.1"));
        assertTrue(throttle.tryBegin("alice", "1.1.1.1"));
        assertTrue(throttle.tryBegin("alice", "1.1.1.1"));
        assertFalse(throttle.tryBegin("alice", "1.1.1.1"));
    }

    @Test
    void sourceIsLimitedAcrossAccounts() {
        LoginThrottle throttle = throttle();
        for (int i = 0; i < 10; i++) {
            assertTrue(throttle.tryBegin("user" + i, "1.1.1.1"));
        }
        assertFalse(throttle.tryBegin("someone", "1.1.1.1"));
        assertTrue(throttle.tryBegin("someone", "2.2.2.2"));
    }

    @Test
    void successClearsTheAccountCountAndRefundsTheSource() {
        LoginThrottle throttle = throttle();
        throttle.tryBegin("alice", "1.1.1.1");
        throttle.tryBegin("alice", "1.1.1.1");
        throttle.tryBegin("alice", "1.1.1.1");
        throttle.succeeded("alice", "1.1.1.1");
        assertTrue(throttle.tryBegin("alice", "1.1.1.1"));
        assertTrue(throttle.tryBegin("alice", "1.1.1.1"));
        assertTrue(throttle.tryBegin("alice", "1.1.1.1"));
        assertFalse(throttle.tryBegin("alice", "1.1.1.1"));
    }

    /** 攻击者用自己的账号成功登录不能清掉来源上别人账号的失败计数。 */
    @Test
    void successDoesNotClearOtherFailuresFromTheSameSource() {
        LoginThrottle throttle = throttle();
        for (int i = 0; i < 9; i++) {
            assertTrue(throttle.tryBegin("victim" + i, "1.1.1.1"));
        }
        assertTrue(throttle.tryBegin("attacker", "1.1.1.1"));
        throttle.succeeded("attacker", "1.1.1.1");
        assertTrue(throttle.tryBegin("attacker", "1.1.1.1"));
        assertFalse(throttle.tryBegin("victim0", "1.1.1.1"));
    }

    @Test
    void limitExpires() {
        LoginThrottle throttle = throttle();
        for (int i = 0; i < 3; i++) {
            throttle.tryBegin("alice", "1.1.1.1");
        }
        now = now.plus(Duration.ofMinutes(16));
        assertTrue(throttle.tryBegin("alice", "1.1.1.1"));
    }

    @Test
    void manyDistinctNamesDoNotGrowWithoutBound() {
        LoginThrottle throttle = throttle();
        for (int i = 0; i < 50_000; i++) {
            throttle.tryBegin("user" + i, "ip" + i);
        }
        assertTrue(throttle.trackedKeys() <= LoginThrottle.MAX_KEYS);
    }
}
