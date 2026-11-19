package tantalum.value.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.value.decision.SessionChange;
import tantalum.value.decision.TextSwapped;
import tantalum.value.event.TextWithdrawn;
import tantalum.value.event.TextWithdrawn.By;
import tantalum.value.event.TextWithdrawn.Swap;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.state.Compaction;
import tantalum.value.state.SessionState;
import tantalum.value.state.Withdrawal;
import tantalum.value.task.TaskOutcome;

/**
 * 撤下文字的换法和会话的视图（memory-forget-by-choice R2a）：只按字面换；历史不改，视图按记下的撤下去换，
 * 只换撤下之前的消息；这份记录跟着一步的变化写进账、读回来一样。
 */
class TextSwapTest {

    private static final String PLATE = "my plate is 7Q2KD";
    private static final List<Swap> SWAPS = List.of(new Swap(PLATE, "[withdrawn]"));
    private static final SessionId SESSION = new SessionId("s");
    private static final Principal USER = new Principal("u");

    @Test
    void everyKindOfTextIsSwappedByTheLetterAndTheRestIsLeftAlone() {
        TextSwap swap = new TextSwap(SWAPS);
        assertEquals("[withdrawn] and [withdrawn]", swap.text(PLATE + " and " + PLATE));
        assertNull(swap.text(null));
        assertEquals(2, swap.places(PLATE + " and " + PLATE));
        assertEquals(0, swap.places((String) null));
        assertEquals(1, swap.places(new TextBlock("x " + PLATE)));
        assertEquals(2, swap.places(List.of(Message.user(PLATE), Message.user("no"), Message.user(PLATE))));

        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("note", new Json.Arr(List.of(new Json.Str("car: " + PLATE), new Json.Num(BigDecimal.ONE))));
        fields.put("on", new Json.Bool(true));
        fields.put("none", new Json.Null());
        Json.Obj arguments = new Json.Obj(fields);
        ToolCallBlock call = (ToolCallBlock) swap.block(new ToolCallBlock("c1", "write", TextSwap.write(arguments), arguments));
        assertEquals("{\"note\":[\"car: [withdrawn]\",1],\"on\":true,\"none\":null}", call.rawArguments());
        ToolCallBlock broken = (ToolCallBlock) swap.block(new ToolCallBlock("c2", "write", "{\"text\": \"" + PLATE, null));
        assertEquals("{\"text\": \"[withdrawn]", broken.rawArguments());
        assertNull(broken.arguments());
        ToolCallBlock untouched = new ToolCallBlock("c3", "read", "{}", new Json.Obj(Map.of()));
        assertSame(untouched, swap.block(untouched));

        assertEquals(new ToolResultBlock("c1", "saved [withdrawn]", false, null), swap.block(new ToolResultBlock("c1", "saved " + PLATE, false, null)));
        assertEquals(new HookTextBlock("h", "[withdrawn]"), swap.block(new HookTextBlock("h", PLATE)));
        assertEquals(new AttachedBlock("memory", "[withdrawn]"), swap.block(new AttachedBlock("memory", PLATE)));
        assertEquals(new TaskResultBlock("t1", TaskOutcome.COMPLETED, "[withdrawn]"), swap.block(new TaskResultBlock("t1", TaskOutcome.COMPLETED, PLATE)));
        SkillBlock skill = new SkillBlock("deploy", PLATE);
        assertSame(skill, swap.block(skill), "a skill's text is the skill file, not something said");
        ReasoningBlock thinking = new ReasoningBlock("claude", new Json.Str(PLATE));
        assertSame(thinking, swap.block(thinking), "signed thinking is not touched");

        Message plain = Message.user("nothing here");
        assertSame(plain, swap.message(plain), "an unchanged message stays the same object");
        assertEquals(List.of(plain, Message.user("[withdrawn]")), swap.messages(List.of(plain, Message.user(PLATE))));
    }

    @Test
    void swapsThatCannotBeCarriedOutAreDropped() {
        assertTrue(new TextSwap(List.of(new Swap(" ", "x"), new Swap("x", " "), new Swap("x", "(x)"))).isEmpty());
        assertFalse(new TextSwap(SWAPS).isEmpty());
        assertEquals("\"a\\\"b\\\\c\\r\\n\\t\\u0001\"", TextSwap.write(new Json.Str("a\"b\\c\r\n\t\u0001")));
    }

    @Test
    void theViewSwapsOnlyWhatWasSaidBeforeTheWithdrawalAndHistoryStaysAsItWas() {
        SessionState session = SessionState.start(SESSION, USER).append(Message.user("first: " + PLATE)).append(Message.user("second"));
        assertSame(session.messages(), session.view(), "nothing withdrawn: the view is the history");

        SessionState withdrawn = session.withdrawing(List.of(new Withdrawal(PLATE, "[withdrawn]", 2, 9)), session.followUps(), null, null)
                .append(Message.user("again: " + PLATE));
        assertEquals(List.of(Message.user("first: [withdrawn]"), Message.user("second"), Message.user("again: " + PLATE)), withdrawn.view());
        assertEquals(Message.user("first: " + PLATE), withdrawn.messages().getFirst(), "history is not rewritten");
        assertEquals(Message.user("again: " + PLATE), withdrawn.said(2));

        SessionState compacted = withdrawn.compacted(new Compaction(List.of(Message.user("summary")), 2));
        assertEquals(List.of(Message.user("summary"), Message.user("again: " + PLATE)), compacted.view());
        assertEquals(new Swap(PLATE, "[withdrawn]"), withdrawn.withdrawn().getFirst().swap());
        assertThrows(IllegalArgumentException.class, () -> new Withdrawal(PLATE, "x", -1, 0));
    }

    @Test
    void theRecordOfAStepCarriesTheWithdrawalsAndPutsThemBack() {
        SessionState before = SessionState.start(SESSION, USER).append(Message.user(PLATE));
        SessionState after = before.withdrawing(List.of(new Withdrawal(PLATE, "[withdrawn]", 1, 4)), before.followUps(), null, null);
        SessionChange change = SessionChange.between(before, after);
        assertFalse(change.isEmpty());
        assertTrue(change.appended().isEmpty(), "history did not change");
        assertEquals(after, change.applyTo(before));
        assertTrue(SessionChange.between(after, after).isEmpty());
        assertNull(SessionChange.between(after, after.append(Message.user("more"))).withdrawn(), "unchanged later on: not written again");
    }

    @Test
    void theEventAndItsFactSayWhatTheyAre() {
        TextWithdrawn byUser = new TextWithdrawn(new RequestId("r"), SWAPS, By.user());
        assertEquals("TextWithdrawn", byUser.type());
        assertEquals(1, byUser.version());
        assertEquals(TextWithdrawn.Actor.USER, byUser.by().actor());
        assertNull(byUser.by().session());
        RunId run = RunId.of(SESSION, 3);
        TextWithdrawn byAgent = new TextWithdrawn(new RequestId("r"), SWAPS, new By(TextWithdrawn.Actor.AGENT, SESSION, run, "c1"));
        assertEquals("c1", byAgent.by().call());
        TextSwapped fact = new TextSwapped(new RunRef(USER, SESSION, run), 2);
        assertEquals("TextSwapped", fact.type());
        assertEquals(2, fact.places());
    }
}
