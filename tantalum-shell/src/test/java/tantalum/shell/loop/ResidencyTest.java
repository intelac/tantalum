package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import tantalum.value.id.SessionId;

class ResidencyTest {

    private static final SessionId S = new SessionId("s");

    @Test
    void aPinnedSessionCannotBeEvicted() {
        Residency residency = new Residency();
        residency.resident(S);
        assertEquals(Residency.State.RESIDENT, residency.pin(S).state());
        assertFalse(residency.evict(S));
        residency.unpin(S);
        assertTrue(residency.evict(S));
    }

    /** 回收和载入不竞争的关键：回收之后第一个事件一定看到 COLD，于是一定会载入。 */
    @Test
    void theEventAfterAnEvictionSeesColdAndAnEvictionAfterAPinFails() {
        Residency residency = new Residency();
        assertEquals(Residency.State.COLD, residency.pin(S).state());
        assertEquals(Residency.State.COLD, residency.pin(S).state(), "still cold until the lane has loaded it");
        residency.resident(S);
        assertEquals(2, residency.pins(S), "marking it resident keeps the pins");
        residency.unpin(S);
        residency.unpin(S);
        assertTrue(residency.evict(S));
        assertEquals(Residency.State.COLD, residency.pin(S).state());
    }

    @Test
    void aFailedSessionStaysFailedAndCannotBeEvicted() {
        Residency residency = new Residency();
        residency.pin(S).state();
        assertTrue(residency.fail(S, "corrupt"), "first failure");
        residency.unpin(S);
        assertEquals("corrupt", residency.failure(S));
        assertFalse(residency.evict(S));
        assertEquals(Residency.State.FAILED, residency.pin(S).state());
    }

    /** ops-hardening-2 D5：重试时间内拒绝，过了就当 COLD 再载入；再失败不算第一次（日志只记警告）。 */
    @Test
    void aFailedSessionIsRetriedAfterTheRetryTime() {
        long[] now = {0};
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now[0]); }
        };
        Residency residency = new Residency(clock, Duration.ofMinutes(1));
        residency.pin(S).state();
        assertTrue(residency.fail(S, "locked"));
        residency.unpin(S);
        now[0] = 59_000;
        assertEquals("locked", residency.failure(S));
        assertEquals(Residency.State.FAILED, residency.pin(S).state());
        residency.unpin(S);
        now[0] = 60_000;
        assertNull(residency.failure(S), "the refusal has expired");
        assertEquals(Residency.State.COLD, residency.pin(S).state(), "the next event loads it again");
        assertFalse(residency.fail(S, "still locked"), "a repeated failure is not the first");
        assertEquals("still locked", residency.failure(S));
        now[0] = 120_000;
        assertEquals(Residency.State.COLD, residency.pin(S).state());
        residency.resident(S);
        assertNull(residency.failure(S));
    }

    @Test
    void anUnbalancedUnpinDoesNotGoNegative() {
        Residency residency = new Residency();
        residency.unpin(S);
        residency.resident(S);
        residency.unpin(S);
        assertEquals(0, residency.pins(S));
        assertNull(residency.failure(S));
    }

    @Test
    void concurrentPinsAndUnpinsBalanceToZero() throws Exception {
        Residency residency = new Residency();
        residency.resident(S);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int thread = 0; thread < 32; thread++) {
                pool.submit(() -> {
                    for (int i = 0; i < 2_000; i++) {
                        residency.pin(S).state();
                        residency.unpin(S);
                    }
                });
            }
        }
        assertEquals(0, residency.pins(S));
        assertTrue(residency.evict(S));
    }

    /** review-fixes 需求 4.1、4.2：钉住时一次读到失败原因；失败过期且无人钉住的条目被回收。 */
    @Test
    void pinReportsTheFailureInOneReadAndAnExpiredFailureIsRecycled() {
        long[] now = {0};
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now[0]); }
        };
        Residency residency = new Residency(clock, Duration.ofMinutes(1));
        residency.pin(S);
        residency.fail(S, "locked");
        residency.unpin(S);
        Residency.Pinned seen = residency.pin(S);
        assertEquals(Residency.State.FAILED, seen.state());
        assertEquals("locked", seen.failure());
        now[0] = 60_000;
        residency.unpin(S);
        assertEquals(0, residency.pins(S));
        assertNull(residency.failure(S));
        assertEquals(new Residency.Pinned(Residency.State.COLD, null), residency.pin(S), "a fresh entry, not the old failure");
        assertTrue(residency.fail(S, "again"), "after recycling, a failure counts as the first again");
    }

    /** 需求 4.3：载入了却没交出去的会话回到冷状态，下一个事件重新载入。 */
    @Test
    void coldForgetsALoadThatWasNeverHandedOver() {
        Residency residency = new Residency();
        residency.pin(S);
        residency.resident(S);
        residency.cold(S);
        assertEquals(1, residency.pins(S), "the pin is still held by the event in flight");
        residency.unpin(S);
        assertEquals(Residency.State.COLD, residency.pin(S).state());
    }
}
