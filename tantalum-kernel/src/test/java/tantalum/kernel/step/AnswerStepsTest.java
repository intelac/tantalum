package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.kernel.LoopFixture.call;
import static tantalum.kernel.LoopFixture.text;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.command.AbortCommands;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.DoWork;
import tantalum.value.command.RunTool;
import tantalum.value.command.StartTimer;
import tantalum.value.decision.Fact;
import tantalum.value.decision.HookDecided;
import tantalum.value.decision.HookReported;
import tantalum.value.decision.QuestionAsked;
import tantalum.value.decision.QuestionSettled;
import tantalum.value.event.Answered;
import tantalum.value.event.Cancel;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.RunInterrupted;
import tantalum.value.event.TimerFired;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.Ask;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Message;
import tantalum.value.model.StopReason;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.question.Answer;
import tantalum.value.question.Question;
import tantalum.value.state.AwaitingAnswer;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;
import tantalum.value.text.LocalText;

/** 等用户回答（waiting-state 需求 1、2、5、8）。 */
class AnswerStepsTest {

    private static final Instant AT = Instant.parse("2026-10-31T01:00:00Z");

    private static Question approval(String callId) {
        return new Question("approval-" + callId, LocalText.of("Allow " + callId + "?"), null, List.of(
                new Question.Choice("once", LocalText.of("Allow once")),
                new Question.Choice("session", LocalText.of("Always in this session")),
                new Question.Choice("deny", LocalText.of("Deny"))));
    }

    /** 工具审批：调 write_file 前问，“一直允许”记进会话，之后不问；没回答按拒绝。 */
    private static final KernelHook APPROVAL = new KernelHook() {
        @Override public String name() { return "approval"; }
        @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
            if (!"write_file".equals(context.call().name())) {
                return Allow.PLAIN;
            }
            Json allowed = state.sessions().get(context.run().session()).note("approval").fields().get("write_file");
            return allowed instanceof Json.Bool bool && bool.value() ? Allow.PLAIN : new Ask(approval(context.call().id()));
        }
        @Override public HookResult onAnswer(KernelState state, HookContext context) {
            if (!context.question().id().startsWith("approval-")) {
                return Allow.PLAIN;
            }
            if (context.answer() == null) {
                return new Stop("no answer in time");
            }
            return switch (context.answer().choice()) {
                case "once" -> Allow.PLAIN;
                case "session" -> Allow.PLAIN.withNote("approval", new Json.Obj(Map.of("write_file", new Json.Bool(true))));
                default -> new Stop("denied by the user");
            };
        }
    };

    /** 在 {@code point} 问一次（记在会话里就不再问），回答后放行。 */
    private static KernelHook askOnceAt(HookPoint point, Duration deadline) {
        Question question = new Question("confirm", LocalText.of("Go on?"), null,
                List.of(new Question.Choice("go", LocalText.of("Go")), new Question.Choice("stop", LocalText.of("Stop"))));
        return new KernelHook() {
            @Override public String name() { return "once"; }
            private HookResult maybeAsk(KernelState state, HookContext context) {
                boolean asked = state.sessions().get(context.run().session()).note("once").fields().containsKey("asked");
                return asked ? Allow.PLAIN : (deadline == null ? new Ask(question) : new Ask(question).after(deadline));
            }
            @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
                return point == HookPoint.BEFORE_MODEL_CALL ? maybeAsk(state, context) : Allow.PLAIN;
            }
            @Override public HookResult beforeRunEnd(KernelState state, HookContext context) {
                return point == HookPoint.BEFORE_RUN_END ? maybeAsk(state, context) : Allow.PLAIN;
            }
            @Override public HookResult onFailure(KernelState state, HookContext context) {
                return point == HookPoint.ON_FAILURE ? maybeAsk(state, context) : Allow.PLAIN;
            }
            @Override public HookResult onAnswer(KernelState state, HookContext context) {
                Allow done = Allow.PLAIN.withNote("once", new Json.Obj(Map.of("asked", new Json.Bool(true))));
                return context.answer() != null && "stop".equals(context.answer().choice()) ? new Stop("user said stop") : done;
            }
        };
    }

    private static LoopFixture started(KernelHook... hooks) {
        LoopFixture loop = new LoopFixture(hooks);
        loop.prompt("hi");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s", List.of(), null, List.of()));
        return loop;
    }

    private static ModelReplied reply(LoopFixture loop, List<ContentBlock> content) {
        return new ModelReplied(loop.onlyCommandId(), content, content.stream().anyMatch(ToolCallBlock.class::isInstance)
                ? StopReason.TOOL_USE : StopReason.END_TURN, "m", null, null, null, null);
    }

    private static LoopFixture atApproval() {
        LoopFixture loop = started(APPROVAL);
        loop.feed(loop.runId(), reply(loop, List.of(call("c1", "write_file"))));
        return loop;
    }

    private static Answered answer(String question, String choice) {
        return new Answered(new SubmissionId("sub-" + question + "-" + choice), new RequestId("r-" + question + "-" + choice), question, Answer.of(choice));
    }

    private static <T extends Fact> List<T> facts(LoopFixture loop, Class<T> type) {
        return loop.last.facts().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static AwaitingAnswer waiting(LoopFixture loop) {
        return assertInstanceOf(AwaitingAnswer.class, loop.run().phase());
    }

    // 需求 1.1、1.2、2.1：工具调用前问 → 等人阶段，没有命令，不计轮数；问题在阶段和事实里
    @Test
    void askBeforeToolCallWaitsWithoutCommands() {
        LoopFixture loop = atApproval();
        AwaitingAnswer waiting = waiting(loop);
        assertEquals("approval-c1", waiting.question().id());
        assertEquals(HookPoint.BEFORE_TOOL_CALL, waiting.origin());
        assertNull(waiting.toolCallId());
        assertNull(waiting.timer());
        assertTrue(loop.last.commands().isEmpty());
        assertEquals(0, loop.run().workRounds());
        assertEquals(List.of("approval-c1"), facts(loop, QuestionAsked.class).stream().map(f -> f.question().id()).toList());
        assertEquals("approval", facts(loop, QuestionAsked.class).getFirst().askedBy());
        assertEquals(HookDecided.Kind.ASK, facts(loop, HookDecided.class).getFirst().kind());
        assertEquals("approval-c1", facts(loop, HookDecided.class).getFirst().reason());
    }

    // 需求 1.3：允许这一次 → 工具发出
    @Test
    void allowOnceRunsTheTool() {
        LoopFixture loop = atApproval();
        loop.feed(loop.runId(), answer("approval-c1", "once"));
        QuestionSettled settled = facts(loop, QuestionSettled.class).getFirst();
        assertEquals(QuestionSettled.Reason.ANSWERED, settled.reason());
        assertEquals("once", settled.answer().choice());
        assertEquals("write_file", assertInstanceOf(RunTool.class, loop.onlyCommand()).call().name());
        assertEquals(ToolSteps.AWAITING_TOOLS, loop.phase());
    }

    // D2：Allow.withNote 记进会话，下一次不问
    @Test
    void sessionWideAllowIsRecordedInTheSession() {
        LoopFixture loop = atApproval();
        loop.feed(loop.runId(), answer("approval-c1", "session"));
        assertEquals(new Json.Bool(true), loop.session().note("approval").fields().get("write_file"));
        assertInstanceOf(RunTool.class, loop.onlyCommand());
        loop.feed(loop.runId(), new ToolFinished(loop.onlyCommandId(), "c1", "ok", false, null));
        loop.feed(loop.runId(), reply(loop, List.of(call("c2", "write_file"))));
        assertEquals("write_file", assertInstanceOf(RunTool.class, loop.onlyCommand()).call().name());
    }

    // 需求 1.3：拒绝只拦这一个调用，模型收到“被拦下”
    @Test
    void denyBlocksOnlyThatCall() {
        LoopFixture loop = atApproval();
        loop.feed(loop.runId(), answer("approval-c1", "deny"));
        assertInstanceOf(CallModel.class, loop.onlyCommand());
        Message results = loop.session().messages().getLast();
        ToolResultBlock blocked = assertInstanceOf(ToolResultBlock.class, results.content().getFirst());
        assertTrue(blocked.error());
        assertTrue(blocked.text().contains("denied by the user"), blocked.text());
    }

    // 需求 1.4：不是当前问题、不是选项 → 忽略并记事实，状态不变
    @Test
    void wrongAnswersAreIgnored() {
        LoopFixture loop = atApproval();
        loop.feed(loop.runId(), answer("approval-other", "once"));
        assertInstanceOf(AwaitingAnswer.class, loop.run().phase());
        assertTrue(loop.last.commands().isEmpty());
        assertTrue(facts(loop, HookReported.class).getFirst().detail().contains("not the current question"));

        loop.feed(loop.runId(), answer("approval-c1", "maybe"));
        assertInstanceOf(AwaitingAnswer.class, loop.run().phase());
        assertTrue(facts(loop, HookReported.class).getFirst().detail().contains("not accepted"));

        loop.feed(loop.runId(), new Answered(new SubmissionId("sub"), new RequestId("r"), "approval-c1", new Answer("once", "with text")));
        assertInstanceOf(AwaitingAnswer.class, loop.run().phase());
    }

    // 需求 1.4：运行不在等人时的回答只记事实
    @Test
    void answerWhenNotWaitingIsIgnored() {
        LoopFixture loop = atApproval();
        loop.feed(loop.runId(), new Cancel(new RequestId("cancel")));
        assertEquals(EndReason.CANCELLED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
        KernelState before = loop.state;
        loop.feed(loop.runId(), answer("approval-c1", "once"));
        assertEquals(before, loop.state);
        assertTrue(facts(loop, HookReported.class).getFirst().detail().contains("ignored"));
    }

    // 需求 8.1：取消 → 问题“没有回答”，运行 CANCELLED
    @Test
    void cancelSettlesTheQuestion() {
        LoopFixture loop = atApproval();
        loop.feed(loop.runId(), new Cancel(new RequestId("cancel")));
        QuestionSettled settled = facts(loop, QuestionSettled.class).getFirst();
        assertEquals(QuestionSettled.Reason.CANCELLED, settled.reason());
        assertNull(settled.answer());
        assertTrue(loop.last.commands().isEmpty());
    }

    // 需求 3.1：重启不中断等人的运行
    @Test
    void restartLeavesAWaitingRunAlone() {
        LoopFixture loop = atApproval();
        KernelState before = loop.state;
        loop.feed(loop.runId(), new RunInterrupted());
        assertEquals(before, loop.state);
        assertTrue(loop.last.facts().isEmpty());
        assertTrue(loop.last.commands().isEmpty());
    }

    // 需求 1.1、1.3：调模型前问；回答后调模型
    @Test
    void askBeforeModelCall() {
        LoopFixture loop = started(askOnceAt(HookPoint.BEFORE_MODEL_CALL, null));
        assertEquals(HookPoint.BEFORE_MODEL_CALL, waiting(loop).origin());
        loop.feed(loop.runId(), answer("confirm", "go"));
        assertInstanceOf(CallModel.class, loop.onlyCommand());
        assertEquals(new Json.Bool(true), loop.session().note("once").fields().get("asked"));
    }

    // 结束前问；回答后完成；选“停”则 STOPPED
    @Test
    void askBeforeRunEnd() {
        LoopFixture loop = started(askOnceAt(HookPoint.BEFORE_RUN_END, null));
        loop.feed(loop.runId(), reply(loop, text("done")));
        assertEquals(HookPoint.BEFORE_RUN_END, waiting(loop).origin());
        loop.feed(loop.runId(), answer("confirm", "go"));
        assertEquals(EndReason.COMPLETED, assertInstanceOf(Ended.class, loop.run().phase()).reason());

        LoopFixture stopped = started(askOnceAt(HookPoint.BEFORE_RUN_END, null));
        stopped.feed(stopped.runId(), reply(stopped, text("done")));
        stopped.feed(stopped.runId(), answer("confirm", "stop"));
        assertEquals(EndReason.STOPPED, assertInstanceOf(Ended.class, stopped.run().phase()).reason());
    }

    // 失败后问；回答后重试
    @Test
    void askOnFailure() {
        LoopFixture loop = started(askOnceAt(HookPoint.ON_FAILURE, null));
        loop.feed(loop.runId(), new ModelFailed(loop.onlyCommandId(), FailureReason.SERVER_ERROR, true, null, "", "503", null, null, null));
        assertEquals(HookPoint.ON_FAILURE, waiting(loop).origin());
        loop.feed(loop.runId(), answer("confirm", "go"));
        assertInstanceOf(CallModel.class, loop.onlyCommand());
    }

    // 工作结果看完要问人；onAnswer 要工作；再问
    @Test
    void askFromWorkResultThenNeedWorkThenAskAgain() {
        KernelHook chain = new KernelHook() {
            @Override public String name() { return "chain"; }
            @Override public HookResult beforeRunEnd(KernelState state, HookContext context) {
                return context.work() == null ? new NeedWork("check", new Json.Null()) : Allow.PLAIN;
            }
            @Override public HookResult onWorkResult(KernelState state, HookContext context) {
                return "check".equals(context.work().workType()) ? new Ask(approval("w")) : Allow.PLAIN;
            }
            @Override public HookResult onAnswer(KernelState state, HookContext context) {
                return switch (context.question().id()) {
                    case "approval-w" -> new NeedWork("second", new Json.Null());
                    default -> Allow.PLAIN;
                };
            }
        };
        LoopFixture loop = started(chain);
        loop.feed(loop.runId(), reply(loop, text("done")));
        assertEquals("check", assertInstanceOf(DoWork.class, loop.onlyCommand()).workType());
        loop.feed(loop.runId(), new WorkDone(loop.onlyCommandId(), "check", new Json.Null(), false, null, null));
        assertEquals(HookPoint.BEFORE_RUN_END, waiting(loop).origin());
        assertEquals(1, loop.run().endWorkRounds());

        loop.feed(loop.runId(), answer("approval-w", "once"));
        assertEquals("second", assertInstanceOf(DoWork.class, loop.onlyCommand()).workType());
        assertEquals(HookPoint.BEFORE_RUN_END, loop.run().workOrigin());
        assertEquals(2, loop.run().endWorkRounds());
        loop.feed(loop.runId(), new WorkDone(loop.onlyCommandId(), "second", new Json.Null(), false, null, null));
        assertEquals(EndReason.COMPLETED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
    }

    // 需求 1.6：onAnswer 再返回 Ask 是新问题
    @Test
    void onAnswerCanAskAgain() {
        KernelHook twice = new KernelHook() {
            @Override public String name() { return "twice"; }
            @Override public HookResult beforeToolCall(KernelState state, HookContext context) { return new Ask(approval("first")); }
            @Override public HookResult onAnswer(KernelState state, HookContext context) {
                return context.question().id().equals("approval-first") ? new Ask(approval("second")) : Allow.PLAIN;
            }
        };
        LoopFixture loop = started(twice);
        loop.feed(loop.runId(), reply(loop, List.of(call("c1", "read_file"))));
        loop.feed(loop.runId(), answer("approval-first", "once"));
        assertEquals("approval-second", waiting(loop).question().id());
        loop.feed(loop.runId(), answer("approval-second", "once"));
        assertInstanceOf(RunTool.class, loop.onlyCommand());
    }

    // 需求 1.5、2.4：期限 → 定时器；到时按没回答；之后的回答忽略；回答在前则中止定时器
    @Test
    void deadlineExpiresAsNoAnswer() {
        LoopFixture loop = started(askOnceAt(HookPoint.BEFORE_MODEL_CALL, Duration.ofMinutes(10)));
        StartTimer timer = assertInstanceOf(StartTimer.class, loop.onlyCommand());
        assertEquals(Duration.ofMinutes(10), timer.delay());
        assertEquals(AT.plus(Duration.ofMinutes(10)), waiting(loop).question().deadline());
        assertEquals(timer.id(), waiting(loop).timer());

        loop.feed(loop.runId(), new TimerFired(new CommandId("other")));
        assertInstanceOf(AwaitingAnswer.class, loop.run().phase());

        loop.feed(loop.runId(), new TimerFired(timer.id()));
        assertEquals(QuestionSettled.Reason.EXPIRED, facts(loop, QuestionSettled.class).getFirst().reason());
        // 没回答 → 钩子放行并记下 → 调模型
        assertInstanceOf(CallModel.class, loop.onlyCommand());
        loop.feed(loop.runId(), answer("confirm", "go"));
        assertTrue(facts(loop, HookReported.class).getFirst().detail().contains("ignored"));
    }

    @Test
    void answerBeforeDeadlineAbortsTheTimer() {
        LoopFixture loop = started(askOnceAt(HookPoint.BEFORE_MODEL_CALL, Duration.ofMinutes(10)));
        CommandId timer = loop.onlyCommandId();
        loop.feed(loop.runId(), answer("confirm", "go"));
        List<Command> commands = loop.last.commands();
        assertEquals(2, commands.size(), commands.toString());
        assertEquals(java.util.Set.of(timer), assertInstanceOf(AbortCommands.class, commands.get(0)).commands());
        assertInstanceOf(CallModel.class, commands.get(1));
    }

    // 需求 5.2、5.3：ask_user 等其他工具先跑完；回答是它的结果
    @Test
    void askUserWaitsForTheOtherToolsFirst() {
        LoopFixture loop = started();
        ToolCallBlock ask = new ToolCallBlock("a1", "ask_user", "{}", new Json.Obj(Map.of(
                "question", new Json.Str("Delete the backups?"),
                "choices", new Json.Arr(List.of(new Json.Str("delete"), new Json.Str("keep"))))));
        loop.feed(loop.runId(), reply(loop, List.of(ask, call("r1", "safe_read"))));
        assertEquals("safe_read", assertInstanceOf(RunTool.class, loop.onlyCommand()).call().name());
        loop.feed(loop.runId(), new ToolFinished(loop.onlyCommandId(), "r1", "contents", false, null));
        AwaitingAnswer waiting = waiting(loop);
        assertEquals("a1", waiting.toolCallId());
        assertNull(waiting.origin());
        assertEquals("ask_user", facts(loop, QuestionAsked.class).getFirst().askedBy());
        assertTrue(waiting.question().freeText());

        loop.feed(loop.runId(), new Answered(new SubmissionId("sub"), new RequestId("r"), "ask-a1", new Answer("keep", "archive to old/")));
        assertInstanceOf(CallModel.class, loop.onlyCommand());
        Message results = loop.session().messages().getLast();
        assertEquals(2, results.content().size());
        ToolResultBlock first = assertInstanceOf(ToolResultBlock.class, results.content().get(0));
        assertEquals("a1", first.toolCallId());
        assertEquals("{\"choice\":\"keep\",\"text\":\"archive to old/\"}", first.text());
        assertEquals("r1", assertInstanceOf(ToolResultBlock.class, results.content().get(1)).toolCallId());
    }

    // 参数不合法：不问人，错误给模型
    @Test
    void askUserWithBadArgumentsFailsImmediately() {
        LoopFixture loop = started();
        loop.feed(loop.runId(), reply(loop, List.of(new ToolCallBlock("a1", "ask_user", "{}", new Json.Obj(Map.of())))));
        assertInstanceOf(CallModel.class, loop.onlyCommand());
        ToolResultBlock result = assertInstanceOf(ToolResultBlock.class, loop.session().messages().getLast().content().getFirst());
        assertTrue(result.error());
        assertTrue(result.text().contains("question"), result.text());
    }

    // 需求 1.6：一轮里两个 ask_user 按顺序问；期限到了结果是 timeout
    @Test
    void twoAskUsersAreAskedOneAfterTheOther() {
        LoopFixture loop = started();
        ToolCallBlock first = new ToolCallBlock("a1", "ask_user", "{}", new Json.Obj(Map.of("question", new Json.Str("One?"))));
        ToolCallBlock second = new ToolCallBlock("a2", "ask_user", "{}", new Json.Obj(Map.of("question", new Json.Str("Two?"),
                "timeout_seconds", new Json.Num(java.math.BigDecimal.valueOf(30)))));
        loop.feed(loop.runId(), reply(loop, List.of(first, second)));
        assertEquals("a1", waiting(loop).toolCallId());
        loop.feed(loop.runId(), new Answered(new SubmissionId("sub1"), new RequestId("r1"), "ask-a1", new Answer(null, "one")));
        assertEquals("a2", waiting(loop).toolCallId());
        StartTimer timer = assertInstanceOf(StartTimer.class, loop.onlyCommand());
        assertEquals(Duration.ofSeconds(30), timer.delay());
        loop.feed(loop.runId(), new TimerFired(timer.id()));
        assertInstanceOf(CallModel.class, loop.onlyCommand());
        List<ContentBlock> results = loop.session().messages().getLast().content();
        assertEquals("{\"text\":\"one\"}", assertInstanceOf(ToolResultBlock.class, results.get(0)).text());
        assertEquals("{\"timeout\":true}", assertInstanceOf(ToolResultBlock.class, results.get(1)).text());
    }

    // 取消在等 ask_user 时：问题“没有回答”，未回答的槽位补合成结果
    @Test
    void cancelWhileAskUserWaits() {
        LoopFixture loop = started();
        loop.feed(loop.runId(), reply(loop, List.of(new ToolCallBlock("a1", "ask_user", "{}",
                new Json.Obj(Map.of("question", new Json.Str("One?")))))));
        loop.feed(loop.runId(), new Cancel(new RequestId("cancel")));
        assertEquals(QuestionSettled.Reason.CANCELLED, facts(loop, QuestionSettled.class).getFirst().reason());
        assertEquals(EndReason.CANCELLED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
        ToolResultBlock synthetic = assertInstanceOf(ToolResultBlock.class, loop.session().messages().getLast().content().getFirst());
        assertTrue(synthetic.error());
    }
}
