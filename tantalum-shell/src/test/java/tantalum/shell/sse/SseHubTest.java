package tantalum.shell.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.shell.journal.EventCodec;
import tantalum.value.decision.HookDecided;
import tantalum.value.decision.PhaseChanged;
import tantalum.value.hook.HookPoint;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ToolCallBlock;
import tools.jackson.databind.json.JsonMapper;

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
        return new SseHub(4, 8, (principal, after) -> List.of(), MAPPER);
    }

    @Test
    void factGoesOnlyToItsOwner() throws Exception {
        SseHub hub = hub();
        SseHub.Subscription alice = hub.subscribe(ALICE, null, null);
        SseHub.Subscription bob = hub.subscribe(BOB, null, null);

        hub.onFact(0, 0, fact(ALICE, "AwaitingModel"));

        SseMessage message = alice.poll(1, TimeUnit.SECONDS);
        assertEquals("0.0", message.id());
        assertEquals("PhaseChanged", message.event());
        assertTrue(message.data().contains("AwaitingModel"), message.data());
        assertNull(bob.poll(50, TimeUnit.MILLISECONDS));
    }

    /** 钩子的决定和其他事实一样推送：事件名是类型名，字段照记录，空字段不写。控制台不认识的类型直接忽略。 */
    @Test
    void hookDecisionIsPushedLikeAnyFact() throws Exception {
        SseHub hub = hub();
        SseHub.Subscription alice = hub.subscribe(ALICE, null, null);

        hub.onFact(3, 1, new HookDecided(run(ALICE), "approval", HookPoint.BEFORE_TOOL_CALL, HookDecided.Kind.STOP, "needs approval",
                0, List.of(), "call-1", null));

        SseMessage message = alice.poll(1, TimeUnit.SECONDS);
        assertEquals("3.1", message.id());
        assertEquals("HookDecided", message.event());
        tools.jackson.databind.JsonNode data = MAPPER.readTree(message.data());
        assertEquals("approval", data.path("hook").asString());
        assertEquals("BEFORE_TOOL_CALL", data.path("point").asString());
        assertEquals("STOP", data.path("kind").asString());
        assertEquals("needs approval", data.path("reason").asString());
        assertEquals("call-1", data.path("toolCall").asString());
        assertEquals(0, data.path("systemChars").asInt());
        assertTrue(data.path("tools").isArray() && data.path("tools").isEmpty(), message.data());
        assertTrue(data.path("workType").isMissingNode(), message.data());
        assertEquals("r-alice", data.path("run").asString());
        assertEquals("s-alice", data.path("session").asString());
    }

    @Test
    void chunkGoesOnlyToItsOwnerAndHasNoId() throws Exception {
        SseHub hub = hub();
        SseHub.Subscription alice = hub.subscribe(ALICE, null, null);
        SseHub.Subscription bob = hub.subscribe(BOB, null, null);

        hub.chunks(run(ALICE)).onText("Hel");

        SseMessage message = alice.poll(1, TimeUnit.SECONDS);
        assertNull(message.id());
        assertEquals("chunk", message.event());
        assertTrue(message.data().contains("Hel"));
        assertNull(bob.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void slowSubscriberIsCutOffWithoutAffectingOthers() throws Exception {
        SseHub hub = hub();
        SseHub.Subscription slow = hub.subscribe(ALICE, null, null);
        SseHub.Subscription fast = hub.subscribe(ALICE, null, null);

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

        SseHub.Subscription again = hub.subscribe(ALICE, null, "0.0");

        assertEquals("1.0", again.poll(1, TimeUnit.SECONDS).id());
        assertEquals("1.1", again.poll(1, TimeUnit.SECONDS).id());
        assertNull(again.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void reconnectResumesInsideASingleEvent() throws Exception {
        SseHub hub = hub();
        hub.onFact(1, 0, fact(ALICE, "b"));
        hub.onFact(1, 1, fact(ALICE, "c"));
        SseHub.Subscription again = hub.subscribe(ALICE, null, "1.0");
        assertEquals("1.1", again.poll(1, TimeUnit.SECONDS).id());
    }

    @Test
    void reconnectBeyondMemoryFallsBackToHistory() throws Exception {
        List<String> asked = new ArrayList<>();
        SseHub hub = new SseHub(4, 8, (principal, after) -> {
            asked.add(principal.value() + "@" + after);
            return List.of(new SseMessage("50.0", "PhaseChanged", "{}"));
        }, MAPPER);
        SseHub.Subscription again = hub.subscribe(ALICE, null, "3.0");
        assertEquals(List.of("alice@3.0"), asked);
        assertEquals("50.0", again.poll(1, TimeUnit.SECONDS).id());
    }

    @Test
    void evictedMemoryFallsBackToHistory() throws Exception {
        List<String> asked = new ArrayList<>();
        SseHub hub = new SseHub(4, 2, (principal, after) -> {
            asked.add(after);
            return List.of();
        }, MAPPER);
        for (int i = 0; i < 5; i++) {
            hub.onFact(i, 0, fact(ALICE, "p"));
        }
        hub.subscribe(ALICE, null, "0.0");
        assertEquals(List.of("0.0"), asked);
    }

    /** 同一序号有多条消息，只挤出了其中一部分：客户端的位置在被挤出的那条之前就必须回放，不能从内存补读而漏掉它。 */
    @Test
    void evictionWithinOneSeqIsTrackedByIndex() throws Exception {
        List<String> asked = new ArrayList<>();
        SseHub hub = new SseHub(4, 2, (principal, after) -> {
            asked.add(after);
            return List.of();
        }, MAPPER);
        hub.onFact(5, 0, fact(ALICE, "a"));
        hub.onFact(5, 1, fact(ALICE, "b"));
        hub.onFact(6, 0, fact(ALICE, "c"));   // 挤出 5.0
        SseHub.Subscription atFiveZero = hub.subscribe(ALICE, null, "5.0");
        assertEquals(List.of(), asked, "5.0 itself was evicted but everything after it is in memory");
        assertEquals("5.1", atFiveZero.poll(1, TimeUnit.SECONDS).id());
        assertEquals("6.0", atFiveZero.poll(1, TimeUnit.SECONDS).id());

        hub.onFact(7, 0, fact(ALICE, "d"));   // 挤出 5.1
        hub.subscribe(ALICE, null, "5.0");
        assertEquals(List.of("5.0"), asked, "5.1 is gone from memory, so 5.0 must replay from the journal");
    }

    /** 序号只在一个用户内可比。全局边界下，序号大的 alice 被挤出后，bob 还在内存里的位置会被误判为要回放。 */
    @Test
    void evictingOneUserDoesNotMoveAnotherUsersPosition() throws Exception {
        List<String> asked = new ArrayList<>();
        SseHub hub = new SseHub(4, 3, (principal, after) -> {
            asked.add(principal.value() + "@" + after);
            return List.of();
        }, MAPPER);
        hub.onFact(5, 0, fact(BOB, "b5"));
        hub.onFact(100, 0, fact(ALICE, "a"));
        hub.onFact(6, 0, fact(BOB, "b6"));
        hub.onFact(101, 0, fact(ALICE, "c")); // 挤出 bob 的 5.0
        hub.onFact(102, 0, fact(ALICE, "d")); // 挤出 alice 的 100.0

        SseHub.Subscription bob = hub.subscribe(BOB, null, "5.0");
        assertEquals(List.of(), asked);
        assertEquals("6.0", bob.poll(1, TimeUnit.SECONDS).id());

        hub.subscribe(ALICE, null, "99.0");
        assertEquals(List.of("alice@99.0"), asked);
    }

    /** 同样的序号属于不同用户：补读不串。 */
    @Test
    void usersWithTheSameSeqDoNotSeeEachOther() throws Exception {
        SseHub hub = new SseHub(4, 8, (principal, after) -> List.of(), MAPPER);
        hub.onFact(1, 0, fact(ALICE, "a"));
        hub.onFact(1, 0, fact(BOB, "b"));
        hub.onFact(2, 0, fact(ALICE, "a"));
        hub.onFact(2, 0, fact(BOB, "b"));
        SseHub.Subscription alice = hub.subscribe(ALICE, null, "1.0");
        assertEquals("2.0", alice.poll(1, TimeUnit.SECONDS).id());
        assertEquals(null, alice.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void failedHistoryReplayDoesNotLeakTheSubscription() {
        SseHub hub = new SseHub(4, 8, (principal, after) -> { throw new IllegalStateException("journal gone"); }, MAPPER);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> hub.subscribe(ALICE, null, "1.0"));
        assertEquals(0, hub.subscriberCount());
    }

    @Test
    void closedSubscriptionsLeaveNoEntryBehind() throws Exception {
        SseHub hub = hub();
        SseHub.Subscription alice = hub.subscribe(ALICE, null, null);
        hub.chunks(run(BOB)).onText("bob never subscribed");
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
        SseHub hub = new SseHub(4, 8, (principal, after) -> history, MAPPER);
        SseHub.Subscription again = hub.subscribe(ALICE, null, "-1.0");
        assertFalse(again.closed());
        for (int i = 0; i < 50; i++) {
            assertEquals(i + ".0", again.poll(1, TimeUnit.SECONDS).id());
        }
    }

    @Test
    void badLastEventIdStartsFromNow() throws Exception {
        SseHub hub = hub();
        hub.onFact(0, 0, fact(ALICE, "a"));
        assertNull(hub.subscribe(ALICE, null, "garbage").poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void closeAllEndsOnlyThatUsersSubscriptions() {
        SseHub hub = hub();
        SseHub.Subscription first = hub.subscribe(ALICE, null, null);
        SseHub.Subscription second = hub.subscribe(ALICE, null, null);
        SseHub.Subscription other = hub.subscribe(BOB, null, null);
        hub.closeAll(ALICE);
        assertTrue(first.closed());
        assertTrue(second.closed());
        assertFalse(other.closed());
    }

    /** 任务 37：退出只断开这一次登录的订阅。 */
    @Test
    void closeLoginEndsOnlyThatLoginsSubscriptions() {
        SseHub hub = hub();
        SseHub.Subscription laptop = hub.subscribe(ALICE, "login-1", null);
        SseHub.Subscription laptopTab = hub.subscribe(ALICE, "login-1", null);
        SseHub.Subscription phone = hub.subscribe(ALICE, "login-2", null);
        SseHub.Subscription other = hub.subscribe(BOB, "login-1", null);
        hub.closeLogin(ALICE, "login-1");
        assertTrue(laptop.closed());
        assertTrue(laptopTab.closed());
        assertFalse(phone.closed());
        assertFalse(other.closed(), "the same login id under another user is a different login");
        hub.closeLogin(ALICE, null);
        assertFalse(phone.closed());
    }

    @Test
    void closedSubscriptionStopsReceiving() throws Exception {
        SseHub hub = hub();
        SseHub.Subscription subscription = hub.subscribe(ALICE, null, null);
        subscription.close();
        hub.onFact(0, 0, fact(ALICE, "a"));
        assertNull(subscription.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void itemGoesOnlyToItsOwnerAndCanBeReadAgainAfterReconnect() throws Exception {
        SseHub hub = hub();
        SseHub.Subscription alice = hub.subscribe(ALICE, null, null);
        SseHub.Subscription bob = hub.subscribe(BOB, null, null);
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

        SseHub.Subscription again = hub.subscribe(ALICE, null, "3.0");
        assertEquals("3.1", again.poll(1, TimeUnit.SECONDS).id());
    }
}
