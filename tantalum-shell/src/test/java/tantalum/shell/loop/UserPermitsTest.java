package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import tantalum.value.event.Cancel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.TimerFired;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;

/** ops-hardening-2 需求 4：停住的事件要等总名额时的边角。 */
class UserPermitsTest {

    private final List<EventEnvelope> admitted = new ArrayList<>();

    private static EventEnvelope event(String user, int i) {
        return new EventEnvelope(EventEnvelope.UNASSIGNED, Instant.EPOCH, new Principal(user), new SessionId("s-" + user), null,
                new Cancel(new RequestId("r" + i)));
    }

    /**
     * A 归还名额的那一刻放行名额被消费者线程抢走（这里用“不还放行名额”模拟）：A 停住的事件进不去，也没有 A 的事件再归还了。
     * 之后随便谁归还一个放行名额，都替 A 试一次，A 的事件这才进去。
     */
    @Test
    void aParkedEventGetsInWhenAnyoneFreesAGlobalPermit() {
        Semaphore global = new Semaphore(1);
        UserPermits permits = new UserPermits(1, global, admitted::add);
        EventEnvelope a1 = event("a", 1);
        assertTrue(permits.take(a1));
        global.acquireUninterruptibly();
        assertFalse(permits.take(event("a", 2)), "a is at the limit");
        assertEquals(1, permits.parkedCount());

        permits.released(a1); // 放行名额没还回来：a:r2 还停着
        assertEquals(1, permits.parkedCount());
        assertTrue(admitted.isEmpty());

        global.release();
        EventEnvelope timerOfB = new EventEnvelope(EventEnvelope.UNASSIGNED, Instant.EPOCH, new Principal("b"), new SessionId("s-b"), null,
                new TimerFired(new CommandId("t")));
        permits.released(timerOfB);
        assertEquals(0, permits.parkedCount());
        assertEquals(1, admitted.size());
        assertEquals(0, global.availablePermits(), "the admitted event took the global permit");
    }

    /** 消费者没拿到放行名额（停机）时退回用户名额，下一个事件不会被误判为到上限。 */
    @Test
    void giveBackUndoesTake() {
        Semaphore global = new Semaphore(4);
        UserPermits permits = new UserPermits(1, global, admitted::add);
        EventEnvelope a1 = event("a", 1);
        assertTrue(permits.take(a1));
        permits.giveBack(a1);
        assertTrue(permits.take(event("a", 2)));
    }
}
