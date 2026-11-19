package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.kernel.LoopFixture.text;
import static tantalum.kernel.step.CompactStepsTest.SETTINGS;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.Replay;
import tantalum.value.decision.Decision;
import tantalum.value.decision.TextSwapped;
import tantalum.value.event.ContextAssembledFixture;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.FollowUpSubmittedFixture;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.TextWithdrawn;
import tantalum.value.event.TextWithdrawn.By;
import tantalum.value.event.TextWithdrawn.Swap;
import tantalum.value.event.ToolFinishedFixture;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.AttachedBlock;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.Compaction;
import tantalum.value.state.ContextSection;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionState;

/**
 * 撤下文字（memory-forget-by-choice R2a）：内核只按字面换，哪里逐字出现原文就换哪里；不加不减消息、不发命令、不改运行的走向；
 * 重复送来、没出现的原文、别人的事件都不改任何东西。
 */
class WithdrawStepsTest {

    private static final String PLATE = "my plate is 7Q2KD";
    private static final String GONE = "[withdrawn]";
    private int next;

    private TextWithdrawn withdraw(String original, String replacement) {
        return new TextWithdrawn(new RequestId("w" + next++), List.of(new Swap(original, replacement)), By.user());
    }

    private static ToolCallBlock write(String id, String content) {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("name", new Json.Str("car"));
        fields.put("content", new Json.Str(content));
        Json.Obj arguments = new Json.Obj(fields);
        return new ToolCallBlock(id, "safe_write", WithdrawSteps.write(arguments), arguments);
    }

    /** 说了车牌，模型调了两个工具（一个把它写进参数），第一个的结果回来了、第二个还没回来。上下文段里也有它。 */
    private static LoopFixture midRun() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("note this: " + PLATE + ", thanks");
        loop.feed(loop.runId(), ContextAssembledFixture.of(loop.onlyCommandId(), "system", List.of(), SETTINGS,
                List.of(new ContextSection("memory-index", "- car: " + PLATE, true))));
        List<ContentBlock> reply = List.of(new ReasoningBlock("claude", new Json.Str("the user said " + PLATE)), new TextBlock("saving"),
                write("t1", "the user's car: " + PLATE + "\nsecond line"), LoopFixture.call("t2", "safe_read"));
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), reply, StopReason.TOOL_USE, "m", null, null, null, null));
        loop.feed(loop.runId(), ToolFinishedFixture.of(loop.last.commands().getFirst().id(), "t1", "Saved: " + PLATE, false, null));
        return loop;
    }

    private static String all(SessionState session) {
        return session.messages() + " " + session.followUps() + " " + session.compaction() + " " + session.context().sections();
    }

    @Test
    void theTextIsSwappedWhereverItAppearsVerbatimAndTheRunGoesOnAsBefore() {
        LoopFixture loop = midRun();
        loop.feed(null, FollowUpSubmittedFixture.of(new SubmissionId("f1"), new RequestId("fr1"), "and again, " + PLATE));
        String phase = loop.phase();
        int count = loop.session().messages().size();

        Decision decision = loop.feed(null, withdraw(PLATE, GONE));

        assertTrue(decision.commands().isEmpty());
        assertEquals(phase, loop.phase());
        assertEquals(count, loop.session().messages().size());
        SessionState session = loop.session();
        assertEquals(Message.user("note this: " + GONE + ", thanks"), session.messages().getFirst());
        assertEquals(List.of(Message.user("and again, " + GONE)), session.followUps());
        assertEquals("- car: " + GONE, session.context().sections().getFirst().body());
        ToolCallBlock call = (ToolCallBlock) session.messages().get(1).content().get(2);
        assertEquals(new Json.Str("the user's car: " + GONE + "\nsecond line"), ((Json.Obj) call.arguments()).fields().get("content"));
        assertEquals("{\"name\":\"car\",\"content\":\"the user's car: " + GONE + "\\nsecond line\"}", call.rawArguments());
        // 还没并进历史的工具结果也换了；还没回来的那个照旧在等
        assertEquals("Saved: " + GONE, loop.run().tools().slots().getFirst().result().text());
        assertFalse(loop.run().tools().allFinished());
        // 带签名的思考块不动
        assertEquals(new ReasoningBlock("claude", new Json.Str("the user said " + PLATE)), session.messages().get(1).content().getFirst());
        // 用户消息 1、参数 1、上下文段 1、排队的提问 1、工具结果 1
        assertEquals(List.of(new TextSwapped(loop.run().ref(), 5)), decision.facts());

        // 第二个工具回来，这一轮照常往下走，历史里的结果是换过的
        loop.feed(loop.runId(), ToolFinishedFixture.of(loop.commands.getLast().id(), "t2", "read", false, null));
        Message results = loop.session().messages().get(2);
        assertEquals(new ToolResultBlock("t1", "Saved: " + GONE, false, null), results.content().getFirst());
        assertFalse(all(loop.session()).replace("the user said " + PLATE, "").contains("7Q2KD"), "only the signed thinking block keeps it");
    }

    @Test
    void aSummaryThatRepeatsItAndARecalledNoteAreSwappedToo() {
        LoopFixture loop = midRun();
        SessionState session = loop.session();
        Message recalled = new Message(Role.USER, List.of(new TextBlock("what was it?"), new AttachedBlock("memory", "car: " + PLATE)), false);
        loop.state = loop.state.with(session.append(recalled)
                .compacted(new Compaction(List.of(Message.user("Summary: the user said " + PLATE + " twice. " + PLATE)), 2)));

        Decision decision = loop.feed(null, withdraw(PLATE, GONE));

        session = loop.session();
        assertEquals(new Compaction(List.of(Message.user("Summary: the user said " + GONE + " twice. " + GONE)), 2), session.compaction());
        assertEquals(new AttachedBlock("memory", "car: " + GONE), session.messages().getLast().content().getLast());
        assertFalse(session.view().toString().contains("7Q2KD"), "the view starts after the summary, so the thinking block is not in it");
        // 用户消息 1、参数 1、上下文段 1、工具结果 1、摘要 2、带回来的记忆 1
        assertEquals(7, ((TextSwapped) decision.facts().getFirst()).places());
    }

    @Test
    void sendingItAgainOrNamingTextThatIsNotThereChangesNothing() {
        LoopFixture loop = midRun();
        loop.feed(null, withdraw(PLATE, GONE));
        KernelState once = loop.state;

        Decision again = loop.feed(null, withdraw(PLATE, GONE));
        assertSame(once, again.newState());
        assertTrue(again.facts().isEmpty());

        Decision absent = loop.feed(null, withdraw("my plate is 7Q2KX", GONE));
        assertSame(once, absent.newState());
        assertTrue(absent.facts().isEmpty());
    }

    @Test
    void swapsThatCannotBeCarriedOutAreIgnored() {
        LoopFixture loop = midRun();
        KernelState before = loop.state;
        loop.feed(null, withdraw("  ", GONE));
        loop.feed(null, withdraw(PLATE, " "));
        // 替换里还带着原文：换了等于没撤，重复送来还会越换越长
        loop.feed(null, withdraw(PLATE, "(" + PLATE + ")"));
        assertSame(before, loop.state);
    }

    @Test
    void someoneElsesEventAndASessionThatNeverRanAreLeftAlone() {
        LoopFixture loop = midRun();
        KernelState before = loop.state;
        EventEnvelope foreign = new EventEnvelope(999, LoopFixture.AT, new Principal("other"), LoopFixture.SESSION, null, withdraw(PLATE, GONE));
        assertSame(before, loop.kernel.step(before, foreign).newState());

        LoopFixture empty = new LoopFixture();
        empty.feed(null, withdraw(PLATE, GONE));
        assertEquals(KernelState.empty(), empty.state);
    }

    @Test
    void replayingTheJournalGivesTheSameStateAndCommands() {
        LoopFixture loop = midRun();
        loop.feed(null, withdraw(PLATE, GONE));
        loop.feed(loop.runId(), ToolFinishedFixture.of(loop.commands.getLast().id(), "t2", "read", false, null));

        Replay.Result replay = Replay.run(loop.kernel, loop.journal);
        assertEquals(loop.state, replay.state());
        assertEquals(Replay.run(loop.kernel, loop.journal), replay);
    }

    @Test
    void argumentsAreWrittenBackAsValidJson() {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("a \"b\"", new Json.Str("line\nbreak\ttab \\ slash \u0001"));
        fields.put("n", new Json.Arr(List.of(new Json.Num(new java.math.BigDecimal("1.50")), new Json.Bool(true), new Json.Null())));
        assertEquals("{\"a \\\"b\\\"\":\"line\\nbreak\\ttab \\\\ slash \\u0001\",\"n\":[1.50,true,null]}", WithdrawSteps.write(new Json.Obj(fields)));
    }
}
