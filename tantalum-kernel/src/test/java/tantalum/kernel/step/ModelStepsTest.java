package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.kernel.LoopFixture.text;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.projection.ContextUpdate;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.DoWork;
import tantalum.value.decision.PhaseChanged;
import tantalum.value.event.Cancel;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.PromptPatch;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.ContextSection;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;

class ModelStepsTest {

    private static KernelHook beforeModel(HookResult result) {
        return new KernelHook() {
            @Override public String name() { return "beforeModel"; }
            @Override public HookResult beforeModelCall(KernelState state, HookContext context) { return result; }
        };
    }

    private static KernelHook beforeEnd(HookResult result) {
        return new KernelHook() {
            @Override public String name() { return "beforeEnd"; }
            @Override public HookResult beforeRunEnd(KernelState state, HookContext context) { return result; }
        };
    }

    private static void toModel(LoopFixture loop) {
        loop.prompt("hi");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), null, List.of()));
    }

    private static ModelReplied reply(LoopFixture loop, String text) {
        return new ModelReplied(loop.onlyCommandId(), text(text), StopReason.END_TURN, "m", null, null, null, null);
    }

    @Test
    void promptStartsARunAndAsksForContext() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("hi");
        assertEquals("AwaitingContext", loop.phase());
        assertInstanceOf(AssembleContext.class, loop.onlyCommand());
        assertEquals(List.of(Message.user("hi")), loop.session().messages());
        assertEquals(new PhaseChanged(loop.run().ref(), "AwaitingContext"), loop.last.facts().getFirst());
    }

    @Test
    void contextAssembledCallsTheModelWithTheFullPrompt() {
        LoopFixture loop = new LoopFixture();
        toModel(loop);
        assertEquals("AwaitingModel", loop.phase());
        CallModel call = assertInstanceOf(CallModel.class, loop.onlyCommand());
        assertEquals("system", call.prompt().system());
        assertEquals(List.of(Message.user("hi")), call.prompt().messages());
    }

    /** hook-patch：补丁不拼进 system，而是提问后面一条 system 消息，块上记着钩子名。 */
    @Test
    void beforeModelCallPatchBecomesASystemMessage() {
        LoopFixture loop = new LoopFixture(beforeModel(new Allow(new PromptPatch("+x", List.of()))));
        toModel(loop);
        CallModel call = assertInstanceOf(CallModel.class, loop.onlyCommand());
        assertEquals("system", call.prompt().system());
        assertEquals(List.of(Message.user("hi"), new Message(Role.SYSTEM, List.of(new tantalum.value.model.HookTextBlock("beforeModel", "+x")), false)),
                call.prompt().messages());
        assertEquals(call.prompt().messages(), loop.session().messages());
    }

    @Test
    void beforeModelCallStopEndsTheRunAsStopped() {
        LoopFixture loop = new LoopFixture(beforeModel(new Stop("quota")));
        toModel(loop);
        Ended ended = assertInstanceOf(Ended.class, loop.run().phase());
        assertEquals(EndReason.STOPPED, ended.reason());
        assertEquals("quota", ended.detail());
        assertEquals(List.of(), loop.last.commands());
    }

    @Test
    void beforeModelCallNeedWorkEntersAwaitingWork() {
        LoopFixture loop = new LoopFixture(beforeModel(new NeedWork("w", new Json.Null())));
        toModel(loop);
        assertEquals("AwaitingWork", loop.phase());
        assertInstanceOf(DoWork.class, loop.onlyCommand());
    }

    /** 回复因输出上限被截断：运行仍完成，但带标记（决定 D5）。 */
    @Test
    void truncatedReplyCompletesWithAMarker() {
        LoopFixture loop = new LoopFixture();
        toModel(loop);
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), text("half of the ans"), StopReason.MAX_TOKENS, "m", null, null, null, null));
        Ended ended = assertInstanceOf(Ended.class, loop.run().phase());
        assertEquals(EndReason.COMPLETED, ended.reason());
        assertEquals(ModelSteps.TRUNCATED, ended.detail());
        assertEquals(2, loop.session().messages().size(), "the partial text is still the reply");
    }

    @Test
    void replyWithoutToolsCompletesTheRun() {
        LoopFixture loop = new LoopFixture();
        toModel(loop);
        loop.feed(loop.runId(), reply(loop, "done"));
        assertEquals(EndReason.COMPLETED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
        assertNull(loop.session().activeRun());
        assertEquals(2, loop.session().messages().size());
    }

    @Test
    void replyRecordsUsage() {
        LoopFixture loop = new LoopFixture();
        toModel(loop);
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), text("x"), StopReason.END_TURN, "m",
                new TokenUsage(5, 1, 0, 0), null, null, null));
        assertEquals(new TokenUsage(5, 1, 0, 0), loop.state.principals().get(LoopFixture.USER)
                .usageOn(java.time.LocalDate.of(2026, 10, 31)));
    }

    @Test
    void pendingSteerCallsTheModelAgain() {
        LoopFixture loop = new LoopFixture();
        toModel(loop);
        CommandId waiting = loop.onlyCommandId();
        loop.feed(null, new SteerSubmitted(new SubmissionId("s"), new RequestId("r"), "also this"));
        assertEquals(List.of(), loop.last.commands());
        loop.feed(loop.runId(), new ModelReplied(waiting, text("x"), StopReason.END_TURN, "m", null, null, null, null));

        assertEquals("AwaitingModel", loop.phase());
        CallModel call = assertInstanceOf(CallModel.class, loop.onlyCommand());
        assertEquals(Message.user("also this"), call.prompt().messages().getLast());
    }

    @Test
    void beforeRunEndNeedWorkEntersAwaitingWork() {
        LoopFixture loop = new LoopFixture(beforeEnd(new NeedWork("citation", new Json.Null())));
        toModel(loop);
        loop.feed(loop.runId(), reply(loop, "done"));
        assertEquals("AwaitingWork", loop.phase());
        assertEquals("citation", assertInstanceOf(DoWork.class, loop.onlyCommand()).workType());
    }

    @Test
    void beforeRunEndStopEndsAsStopped() {
        LoopFixture loop = new LoopFixture(beforeEnd(new Stop("no")));
        toModel(loop);
        loop.feed(loop.runId(), reply(loop, "done"));
        assertEquals(EndReason.STOPPED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
    }

    /** 需求 3.1：分段的事件只把变化追加成消息；没有变化不追加。 */
    @Test
    void sectionChangesAppendOnlyTheDifference() {
        var one = List.of(new tantalum.value.state.ContextSection("memory-index", "- a", true));
        var two = List.of(new tantalum.value.state.ContextSection("memory-index", "- a\n- b", true));
        LoopFixture loop = new LoopFixture();
        loop.prompt("one");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "sys", List.of(), null, one));
        loop.feed(loop.runId(), reply(loop, "a"));
        loop.prompt("two");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "sys", List.of(), null, one));
        assertEquals(Message.user("two"), assertInstanceOf(CallModel.class, loop.onlyCommand()).prompt().messages().getLast());
        loop.feed(loop.runId(), reply(loop, "b"));
        loop.prompt("three");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "sys", List.of(), null, two));
        Message update = assertInstanceOf(CallModel.class, loop.onlyCommand()).prompt().messages().getLast();
        String text = ((tantalum.value.model.TextBlock) update.content().getFirst()).text();
        assertTrue(ContextUpdate.isUpdate(text), text);
        assertTrue(text.contains("Added:\n- b"), text);
        assertTrue(!text.contains("- a"), text);
    }

    /** 所有模型相同：第二个运行的 system 和工具逐字节不变，可变内容的变化只作为追加消息出现。 */
    @Test
    void laterRunsKeepTheFrozenContextAndAppendChanges() {
        var idx1 = List.of(new ContextSection("memory-index", "idx1", false));
        var idx2 = List.of(new ContextSection("memory-index", "idx2", false));
        LoopFixture loop = new LoopFixture();
        loop.prompt("one");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "sys idx1", List.of(), null, idx1));
        loop.feed(loop.runId(), reply(loop, "a"));

        loop.prompt("two");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "sys idx1", List.of(), null, idx1));
        CallModel same = assertInstanceOf(CallModel.class, loop.onlyCommand());
        assertEquals("sys idx1", same.prompt().system());
        assertEquals(Message.user("two"), same.prompt().messages().getLast());
        loop.feed(loop.runId(), reply(loop, "b"));

        loop.prompt("three");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "sys idx2", List.of(
                new tantalum.value.model.ToolSpec("late", "d", new Json.Null())), null, idx2));
        CallModel changed = assertInstanceOf(CallModel.class, loop.onlyCommand());
        assertEquals("sys idx1", changed.prompt().system());
        assertEquals(List.of(), changed.prompt().tools());
        assertEquals(ContextUpdate.message(idx2.getFirst().tagged()), changed.prompt().messages().getLast());
        loop.feed(loop.runId(), reply(loop, "c"));

        // 没再变就不重复说明；清空也要说明。
        loop.prompt("four");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "sys idx2", List.of(), null, idx2));
        assertEquals(Message.user("four"), assertInstanceOf(CallModel.class, loop.onlyCommand()).prompt().messages().getLast());
        loop.feed(loop.runId(), reply(loop, "d"));
        loop.prompt("five");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "sys", List.of(), null, List.of()));
        assertEquals(ContextUpdate.message("memory-index no longer applies; ignore what you were given earlier."),
                assertInstanceOf(CallModel.class, loop.onlyCommand()).prompt().messages().getLast());
    }

    /** 不是 Cancel 引起的中断（线程被别的原因打断）：运行不能永远停在 AwaitingModel。 */
    @Test
    void interruptedAwaitedCallEndsTheRunAsCancelledAndKeepsThePartialText() {
        LoopFixture loop = new LoopFixture();
        toModel(loop);
        loop.feed(loop.runId(), new ModelInterrupted(loop.onlyCommandId(), "partial", null, null, null));

        Message message = loop.session().messages().getLast();
        assertTrue(message.interrupted());
        assertEquals(text("partial"), message.content());
        Ended ended = assertInstanceOf(Ended.class, loop.run().phase());
        assertEquals(EndReason.CANCELLED, ended.reason());
        assertNull(loop.session().activeRun());
    }

    @Test
    void emptyInterruptedTextAddsNoMessageButStillEndsTheRun() {
        LoopFixture loop = new LoopFixture();
        toModel(loop);
        loop.feed(loop.runId(), new ModelInterrupted(loop.onlyCommandId(), "", null, null, null));
        assertEquals(1, loop.session().messages().size());
        assertTrue(loop.run().isEnded());
    }

    /** Cancel 之后迟到的中断结果：会话还没开新运行，用户看到的文本留在历史里。 */
    @Test
    void lateInterruptAfterCancelKeepsThePartialTextWhenNoNewRunStarted() {
        LoopFixture loop = new LoopFixture();
        toModel(loop);
        CommandId call = loop.onlyCommandId();
        loop.feed(loop.runId(), new Cancel(new RequestId("c")));
        loop.feed(loop.runId(), new ModelInterrupted(call, "partial", null, null, null));

        Message message = loop.session().messages().getLast();
        assertTrue(message.interrupted());
        assertEquals(text("partial"), message.content());
    }

    /** Cancel 时已有排队提交，新运行已开始：迟到的部分回复不能插到新提示后面。 */
    @Test
    void lateInterruptIsIgnoredOnceANewRunStarted() {
        LoopFixture loop = new LoopFixture();
        toModel(loop);
        CommandId call = loop.onlyCommandId();
        RunId first = loop.runId();
        loop.prompt("second");
        loop.feed(first, new Cancel(new RequestId("c")));
        assertEquals("AwaitingContext", loop.phase());
        KernelState before = loop.state;

        loop.feed(first, new ModelInterrupted(call, "partial", null, null, null));

        assertSame(before, loop.state);
        assertEquals(Message.user("second"), loop.session().messages().getLast());
    }

    @Test
    void interruptForAnotherCommandWhileRunningChangesNothing() {
        LoopFixture loop = new LoopFixture();
        toModel(loop);
        KernelState before = loop.state;
        loop.feed(loop.runId(), new ModelInterrupted(new CommandId("old"), "partial", null, null, null));
        assertSame(before, loop.state);
        assertEquals("AwaitingModel", loop.phase());
    }

    /** 模型说 TOOL_USE 却没给工具调用：按普通回复结束，不能追加空的用户消息再调模型。 */
    @Test
    void toolUseStopWithoutCallsCompletesTheRun() {
        LoopFixture loop = new LoopFixture();
        toModel(loop);
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), text("x"), StopReason.TOOL_USE, "m", null, null, null, null));
        assertEquals(EndReason.COMPLETED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
        assertEquals(List.of(), loop.last.commands());
    }

    /** 运行被取消时模型还没看到的插话不能丢：追加为用户消息，下一个运行会看到。 */
    @Test
    void pendingSteersJoinTheHistoryWhenTheRunIsCancelled() {
        LoopFixture loop = new LoopFixture();
        toModel(loop);
        loop.feed(null, new SteerSubmitted(new SubmissionId("s"), new RequestId("r"), "also this"));
        loop.feed(loop.runId(), new Cancel(new RequestId("c")));

        assertEquals(Message.user("also this"), loop.session().messages().getLast());
        assertEquals(List.of(), loop.run().pendingSteers());
        loop.prompt("next");
        CallModel call = null;
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s", List.of(), null, List.of()));
        call = assertInstanceOf(CallModel.class, loop.onlyCommand());
        assertEquals(List.of(Message.user("hi"), Message.user("also this"), Message.user("next")),
                call.prompt().messages());
    }
}
