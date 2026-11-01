package tantalum.shell.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.shell.journal.EventCodec;
import tantalum.value.json.Json;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.decision.PhaseChanged;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.Map;

class SseHubTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final JsonMapper MAPPER = EventCodec.withBuiltins().mapper();

    private static RunRef run(Principal principal) {
        return new RunRef(principal, new SessionId("s-" + principal.value()), new RunId("r-" + principal.value()));
    }

    private static PhaseChanged fact(Principal principal, String phase) {
        return new PhaseChanged(run(principal), phase);
    }

    private static SseHub hub() {
        return new SseHub(0, 4, 8, (principal, after) -> List.of(), MAPPER);
    }

    @Test
    void factGoesOnlyToItsOwner() throws Exception {
        SseHub hub = hub();
        SseHub.Subscription alice = hub.subscribe(ALICE, null);
        SseHub.Subscription bob = hub.subscribe(BOB, null);

        hub.onFact(0, 0, fact(ALICE, "AwaitingModel"));

        SseMessage message = alice.poll(1, TimeUnit.SECONDS);
        assertEquals("0.0", message.id());
        assertEquals("PhaseChanged", message.event());
        assertTrue(message.data().contains("AwaitingModel"), message.data());
        assertNull(bob.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void chunkGoesOnlyToItsOwnerAndHasNoId() throws Exception {
        SseHub hub = hub();
        SseHub.Subscription alice = hub.subscribe(ALICE, null);
        SseHub.Subscription bob = hub.subscribe(BOB, null);

        hub.onChunk(run(ALICE), "text", "Hel");

        SseMessage message = alice.poll(1, TimeUnit.SECONDS);
        assertNull(message.id());
        assertEquals("chunk", message.event());
        assertTrue(message.data().contains("Hel"));
        assertNull(bob.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void slowSubscriberIsCutOffWithoutAffectingOthers() throws Exception {
        SseHub hub = hub();
        SseHub.Subscription slow = hub.subscribe(ALICE, null);
        SseHub.Subscription fast = hub.subscribe(ALICE, null);

        for (int i = 0; i < 6; i++) {
            hub.onFact(i, 0, fact(ALICE, "p" + i));
            assertEquals("" + i + ".0", fast.poll(1, TimeUnit.SECONDS).id());
        }

        assertTrue(slow.closed());
        assertFalse(fast.closed());
    }

    @Test
    void reconnectReadsWhatItMissedFromMemory() throws Exception {
        SseHub hub = hub();
        hub.onFact(0, 0, fact(ALICE, "a"));
        hub.onFact(1, 0, fact(ALICE, "b"));
        hub.onFact(1, 1, fact(ALICE, "c"));
        hub.onFact(2, 0, fact(BOB, "other"));

        SseHub.Subscription again = hub.subscribe(ALICE, "0.0");

        assertEquals("1.0", again.poll(1, TimeUnit.SECONDS).id());
        assertEquals("1.1", again.poll(1, TimeUnit.SECONDS).id());
        assertNull(again.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void reconnectResumesInsideASingleEvent() throws Exception {
        SseHub hub = hub();
        hub.onFact(1, 0, fact(ALICE, "b"));
        hub.onFact(1, 1, fact(ALICE, "c"));
        SseHub.Subscription again = hub.subscribe(ALICE, "1.0");
        assertEquals("1.1", again.poll(1, TimeUnit.SECONDS).id());
    }

    @Test
    void reconnectBeyondMemoryFallsBackToHistory() throws Exception {
        List<String> asked = new ArrayList<>();
        SseHub hub = new SseHub(100, 4, 8, (principal, after) -> {
            asked.add(principal.value() + "@" + after);
            return List.of(new SseMessage("50.0", "PhaseChanged", "{}"));
        }, MAPPER);
        SseHub.Subscription again = hub.subscribe(ALICE, "3.0");
        assertEquals(List.of("alice@3.0"), asked);
        assertEquals("50.0", again.poll(1, TimeUnit.SECONDS).id());
    }

    @Test
    void evictedMemoryFallsBackToHistory() throws Exception {
        List<String> asked = new ArrayList<>();
        SseHub hub = new SseHub(0, 4, 2, (principal, after) -> {
            asked.add(after);
            return List.of();
        }, MAPPER);
        for (int i = 0; i < 5; i++) {
            hub.onFact(i, 0, fact(ALICE, "p"));
        }
        hub.subscribe(ALICE, "0.0");
        assertEquals(List.of("0.0"), asked);
    }

    /** 同一序号有多条消息，只挤出了其中一部分：客户端的位置在被挤出的那条之前就必须回放，不能从内存补读而漏掉它。 */
    @Test
    void evictionWithinOneSeqIsTrackedByIndex() throws Exception {
        List<String> asked = new ArrayList<>();
        SseHub hub = new SseHub(0, 4, 2, (principal, after) -> {
            asked.add(after);
            return List.of();
        }, MAPPER);
        hub.onFact(5, 0, fact(ALICE, "a"));
        hub.onFact(5, 1, fact(ALICE, "b"));
        hub.onFact(6, 0, fact(ALICE, "c"));   // 挤出 5.0
        SseHub.Subscription atFiveZero = hub.subscribe(ALICE, "5.0");
        assertEquals(List.of(), asked, "5.0 itself was evicted but everything after it is in memory");
        assertEquals("5.1", atFiveZero.poll(1, TimeUnit.SECONDS).id());
        assertEquals("6.0", atFiveZero.poll(1, TimeUnit.SECONDS).id());

        hub.onFact(7, 0, fact(ALICE, "d"));   // 挤出 5.1
        hub.subscribe(ALICE, "5.0");
        assertEquals(List.of("5.0"), asked, "5.1 is gone from memory, so 5.0 must replay from the journal");
    }

    @Test
    void failedHistoryReplayDoesNotLeakTheSubscription() {
        SseHub hub = new SseHub(100, 4, 8, (principal, after) -> { throw new IllegalStateException("journal gone"); }, MAPPER);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> hub.subscribe(ALICE, "1.0"));
        assertEquals(0, hub.subscriberCount());
    }

    @Test
    void closedSubscriptionsLeaveNoEntryBehind() throws Exception {
        SseHub hub = hub();
        SseHub.Subscription alice = hub.subscribe(ALICE, null);
        hub.onChunk(run(BOB), "text", "bob never subscribed");
        hub.closeAll(BOB);
        assertEquals(1, hub.subscriberCount());
        alice.close();
        assertEquals(0, hub.subscriberCount());
    }

    @Test
    void catchUpLargerThanTheQueueIsNotCutOff() throws Exception {
        List<SseMessage> history = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            history.add(new SseMessage(i + ".0", "PhaseChanged", "{}"));
        }
        SseHub hub = new SseHub(100, 4, 8, (principal, after) -> history, MAPPER);
        SseHub.Subscription again = hub.subscribe(ALICE, "-1.0");
        assertFalse(again.closed());
        for (int i = 0; i < 50; i++) {
            assertEquals(i + ".0", again.poll(1, TimeUnit.SECONDS).id());
        }
    }

    @Test
    void badLastEventIdStartsFromNow() throws Exception {
        SseHub hub = hub();
        hub.onFact(0, 0, fact(ALICE, "a"));
        assertNull(hub.subscribe(ALICE, "garbage").poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void closeAllEndsOnlyThatUsersSubscriptions() {
        SseHub hub = hub();
        SseHub.Subscription first = hub.subscribe(ALICE, null);
        SseHub.Subscription second = hub.subscribe(ALICE, null);
        SseHub.Subscription other = hub.subscribe(BOB, null);
        hub.closeAll(ALICE);
        assertTrue(first.closed());
        assertTrue(second.closed());
        assertFalse(other.closed());
    }

    @Test
    void closedSubscriptionStopsReceiving() throws Exception {
        SseHub hub = hub();
        SseHub.Subscription subscription = hub.subscribe(ALICE, null);
        subscription.close();
        hub.onFact(0, 0, fact(ALICE, "a"));
        assertNull(subscription.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void itemGoesOnlyToItsOwnerAndCanBeReadAgainAfterReconnect() throws Exception {
        SseHub hub = hub();
        SseHub.Subscription alice = hub.subscribe(ALICE, null);
        SseHub.Subscription bob = hub.subscribe(BOB, null);
        TranscriptItem.ToolStep step = TranscriptItem.ToolStep.waiting("3.1", new SessionId("s-alice"), new RunId("r-alice"),
                Instant.EPOCH, new ToolCallBlock("c1", "read_file", "{\"path\":\"a\"}", new Json.Obj(Map.of("path", new Json.Str("a")))));

        hub.onFact(3, 0, fact(ALICE, "AwaitingTools"));
        hub.onItem(3, 1, ALICE, step.running("g3", Instant.EPOCH));

        assertEquals("3.0", alice.poll(1, TimeUnit.SECONDS).id());
        SseMessage message = alice.poll(1, TimeUnit.SECONDS);
        assertEquals("3.1", message.id());
        assertEquals("item", message.event());
        assertTrue(message.data().contains("\"kind\":\"tool\""), message.data());
        assertTrue(message.data().contains("\"session\":\"s-alice\""), message.data());
        assertTrue(message.data().contains("\"status\":\"RUNNING\""), message.data());
        assertNull(bob.poll(50, TimeUnit.MILLISECONDS));

        SseHub.Subscription again = hub.subscribe(ALICE, "3.0");
        assertEquals("3.1", again.poll(1, TimeUnit.SECONDS).id());
    }
}
