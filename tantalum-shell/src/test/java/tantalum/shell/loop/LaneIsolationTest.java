package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tantalum.value.event.Cancel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;

/** 需求 2.9：一个用户的文件写得很慢，其他用户不受影响；慢用户自己仍按序。 */
class LaneIsolationTest {

    private static EventEnvelope event(String user, int i) {
        return new EventEnvelope(EventEnvelope.UNASSIGNED, Instant.EPOCH, new Principal(user), new SessionId("s-" + user + "-" + i % 2), null,
                new Cancel(new RequestId(user + i)));
    }

    @Test
    void stuckUserDoesNotHoldBackOthersAndKeepsItsOwnOrder() throws Exception {
        CountDownLatch unstick = new CountDownLatch(1);
        List<EventEnvelope> reached = new CopyOnWriteArrayList<>();
        ConcurrentHashMap<Principal, AtomicLong> counters = new ConcurrentHashMap<>();
        Sequence sequence = principal -> counters.computeIfAbsent(principal, ignored -> new AtomicLong()).getAndIncrement();
        JournalDispatcher[] holder = new JournalDispatcher[1];
        JournalDispatcher dispatcher = new JournalDispatcher(256, sequence, (principal, session, events) -> {
            if (principal.value().equals("slow")) {
                try {
                    unstick.await();
                } catch (InterruptedException stop) {
                    throw new IOException(stop);
                }
            }
        }, envelope -> {
            reached.add(envelope);
            holder[0].released();
            return true;
        });
        holder[0] = dispatcher;
        InputRing input = new InputRing(1024, dispatcher);
        input.start();

        int perUser = 50;
        for (int i = 0; i < perUser; i++) {
            assertTrue(input.tryPublish(event("slow", i)));
        }
        Thread.sleep(50);
        for (int i = 0; i < perUser; i++) {
            assertTrue(input.tryPublish(event("fast", i)));
        }
        for (int i = 0; i < 1000 && reached.size() < perUser; i++) {
            Thread.sleep(5);
        }
        assertEquals(perUser, reached.size(), "the fast user must get through while the slow one is stuck");
        assertTrue(reached.stream().allMatch(envelope -> envelope.principal().value().equals("fast")));

        unstick.countDown();
        for (int i = 0; i < 1000 && reached.size() < 2 * perUser; i++) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertEquals(2 * perUser, reached.size());
        List<EventEnvelope> slow = reached.stream().filter(envelope -> envelope.principal().value().equals("slow")).toList();
        for (int i = 0; i < perUser; i++) {
            assertEquals(i, slow.get(i).seq());
            assertEquals("slow" + i, ((Cancel) slow.get(i).event()).request().value());
        }
        input.shutdown();
    }
}
