package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.RunTool;
import tantalum.value.command.StartTask;
import tantalum.value.command.StopTask;
import tantalum.value.decision.Decision;
import tantalum.value.event.Answered;
import tantalum.value.event.Cancel;
import tantalum.value.event.ContextAssembledFixture;
import tantalum.value.event.FollowUpSubmittedFixture;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.SteerSubmittedFixture;
import tantalum.value.event.TaskAssigned;
import tantalum.value.event.TaskCancelled;
import tantalum.value.event.TaskEnded;
import tantalum.value.event.TaskStopRequested;
import tantalum.value.hook.Allow;
import tantalum.value.hook.Ask;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.StopReason;
import tantalum.value.model.TaskResultBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.question.Answer;
import tantalum.value.question.Question;
import tantalum.value.state.AwaitingAnswer;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;
import tantalum.value.task.OpenTask;
import tantalum.value.task.TaskLimits;
import tantalum.value.task.TaskOutcome;
import tantalum.value.task.TaskTools;
import tantalum.value.text.LocalText;

/**
 * 任务和别的机制碰在一起时（subagent 需求 1.3、3.4、5、6.3、7）：钩子拦截和审批、等用户回答、撤销、排队的提问、
 * 无人说话的计数。每一条都是“结果不能丢、不能多、名单要对”。
 */
class TaskEdgeStepsTest {

    private static ToolCallBlock task(String callId, String prompt) {
        return new ToolCallBlock(callId, TaskTools.TASK, "{}", new Json.Obj(Map.of(TaskTools.PROMPT, new Json.Str(prompt))));
    }

    private static ToolCallBlock askUser(String callId) {
        return new ToolCallBlock(callId, "ask_user", "{}", new Json.Obj(Map.of("question", new Json.Str("Which one?"))));
    }

    private static void ask(LoopFixture loop, String text) {
        loop.prompt(text);
        loop.feed(loop.runId(), ContextAssembledFixture.of(loop.onlyCommandId(), "s", List.of(), null, List.of()));
    }

    private static CommandId modelCall(LoopFixture loop) {
        for (int i = loop.commands.size() - 1; i >= 0; i--) {
            if (loop.commands.get(i) instanceof CallModel call && call.run().session().equals(LoopFixture.SESSION)) {
                return call.id();
            }
        }
        throw new AssertionError("no model call yet");
    }

    private static Decision calls(LoopFixture loop, ContentBlock... calls) {
        return loop.feed(loop.runId(), new ModelReplied(modelCall(loop), List.of(calls), StopReason.TOOL_USE, "m", null, null, null, null));
    }

    private static Decision done(LoopFixture loop, String text) {
        return loop.feed(loop.runId(), new ModelReplied(modelCall(loop), LoopFixture.text(text), StopReason.END_TURN, "m", null, null, null, null));
    }

    /** 自动开的那一轮：先给上下文，再让模型说完。 */
    private static void finishAutoRun(LoopFixture loop, Decision started) {
        AssembleContext assemble = started.commands().stream().filter(AssembleContext.class::isInstance).map(AssembleContext.class::cast)
                .findFirst().orElseThrow();
        loop.feed(loop.runId(), ContextAssembledFixture.of(assemble.id(), "s", List.of(), null, List.of()));
        done(loop, "noted");
    }

    private static String resultOf(LoopFixture loop, String callId) {
        for (Message message : loop.session().messages()) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolResultBlock result && result.toolCallId().equals(callId)) {
                    return (result.error() ? "ERROR " : "") + result.text();
                }
            }
        }
        throw new AssertionError("no result for " + callId);
    }

    private static List<TaskResultBlock> resultsSeen(LoopFixture loop) {
        return loop.session().messages().stream().flatMap(message -> message.content().stream())
                .filter(TaskResultBlock.class::isInstance).map(TaskResultBlock.class::cast).toList();
    }

    private static KernelHook onTask(HookResult result) {
        return new KernelHook() {
            @Override public String name() { return "policy"; }
            @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
                return TaskTools.TASK.equals(context.call().name()) ? result : Allow.PLAIN;
            }
            @Override public HookResult onAnswer(KernelState state, HookContext context) {
                return context.answer() != null && "go".equals(context.answer().choice()) ? Allow.PLAIN : new Stop("denied by the user");
            }
        };
    }

    // ---- 钩子 ----

    @Test
    void aHookCanForbidStartingATask() {
        LoopFixture loop = new LoopFixture(onTask(new Stop("no tasks on this server")));
        ask(loop, "go");

        Decision decision = calls(loop, task("c1", "Check module A."));

        assertEquals("ERROR Tool call blocked: no tasks on this server", resultOf(loop, "c1"));
        assertTrue(decision.commands().stream().noneMatch(StartTask.class::isInstance));
        assertTrue(loop.session().tasks().open().isEmpty());
        assertEquals(0, loop.session().tasks().unattendedTasks());
    }

    @Test
    void aHookCanAskTheUserBeforeATaskIsStarted() {
        Question question = new Question("start-task", LocalText.of("Start a task?"), null,
                List.of(new Question.Choice("go", LocalText.of("Go")), new Question.Choice("no", LocalText.of("No"))));
        LoopFixture loop = new LoopFixture(onTask(new Ask(question)));
        ask(loop, "go");
        Decision asked = calls(loop, task("c1", "Check module A."));

        // 等用户回答：还没放出
        assertInstanceOf(AwaitingAnswer.class, loop.run().phase());
        assertTrue(asked.commands().stream().noneMatch(StartTask.class::isInstance));
        assertTrue(loop.session().tasks().open().isEmpty());

        Decision answered = loop.feed(loop.runId(), new Answered(new SubmissionId("a"), new RequestId("a"), "start-task", Answer.of("go")));

        assertEquals(1, answered.commands().stream().filter(StartTask.class::isInstance).count());
        assertEquals(1, loop.session().tasks().open().size());
        assertTrue(resultOf(loop, "c1").startsWith("Started task"));
    }

    @Test
    void whenTheUserSaysNoNothingIsStarted() {
        Question question = new Question("start-task", LocalText.of("Start a task?"), null,
                List.of(new Question.Choice("go", LocalText.of("Go")), new Question.Choice("no", LocalText.of("No"))));
        LoopFixture loop = new LoopFixture(onTask(new Ask(question)));
        ask(loop, "go");
        calls(loop, task("c1", "Check module A."));

        Decision answered = loop.feed(loop.runId(), new Answered(new SubmissionId("a"), new RequestId("a"), "start-task", Answer.of("no")));

        assertTrue(answered.commands().stream().noneMatch(StartTask.class::isInstance));
        assertTrue(loop.session().tasks().open().isEmpty());
        assertEquals("ERROR Tool call blocked: denied by the user", resultOf(loop, "c1"));
    }

    // ---- 和别的工具在同一次回复里 ----

    @Test
    void tasksAndOtherToolsInOneReplyEachGoTheirOwnWay() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");

        Decision decision = calls(loop, task("c1", "one"), LoopFixture.call("c2", "safe-read"), task("c3", "two"));

        // 两个任务当场有结果；普通工具照常发出，这一轮等的只是它
        assertEquals(2, decision.commands().stream().filter(StartTask.class::isInstance).count());
        assertEquals(1, decision.commands().stream().filter(RunTool.class::isInstance).count());
        assertEquals(ToolSteps.AWAITING_TOOLS, loop.phase());
        List<String> ids = loop.session().tasks().open().stream().map(OpenTask::id).toList();
        assertEquals(2, ids.size());
        assertFalse(ids.get(0).equals(ids.get(1)), ids.toString());
    }

    @Test
    void aTaskCanBeStoppedInTheSameReplyThatNamesIt() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        calls(loop, task("c1", "one"));
        String id = loop.session().tasks().open().getFirst().id();

        Decision decision = calls(loop, task("c2", "two"), new ToolCallBlock("c3", TaskTools.TASK_STOP, "{}",
                new Json.Obj(Map.of(TaskTools.ID, new Json.Str(id)))));

        assertEquals(1, decision.commands().stream().filter(StartTask.class::isInstance).count());
        assertEquals(id, decision.commands().stream().filter(StopTask.class::isInstance).map(StopTask.class::cast).findFirst().orElseThrow().task());
        assertEquals(2, loop.session().tasks().open().size());
    }

    // ---- 结果到的时候这一轮正停在别处 ----

    @Test
    void aResultThatArrivesWhileTheRunWaitsForTheUserIsKeptForWhenTheyAnswer() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        calls(loop, task("c1", "one"), askUser("q1"));
        String id = loop.session().tasks().open().getFirst().id();
        assertInstanceOf(AwaitingAnswer.class, loop.run().phase());
        RunId run = loop.runId();

        Decision arrived = loop.feed(null, new TaskEnded(id, TaskOutcome.COMPLETED, "one done"));

        // 没有另开一轮；这一轮还在等回答，结果先留着
        assertEquals(run, loop.session().activeRun());
        assertInstanceOf(AwaitingAnswer.class, loop.run().phase());
        assertTrue(arrived.commands().stream().noneMatch(AssembleContext.class::isInstance));
        assertTrue(resultsSeen(loop).isEmpty());

        String asked = ((AwaitingAnswer) loop.run().phase()).question().id();
        loop.feed(run, new Answered(new SubmissionId("a"), new RequestId("a"), asked, new Answer(null, "the first")));

        // 回答之后调模型：结果并进了历史，模型看得到
        assertEquals(List.of(new TaskResultBlock(id, TaskOutcome.COMPLETED, "one done")), resultsSeen(loop));
        assertEquals(ModelSteps.AWAITING_MODEL, loop.phase());
    }

    @Test
    void aResultThatWasWaitingToJoinARunIsNotLostWhenThatRunIsStopped() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        calls(loop, task("c1", "one"), LoopFixture.call("c2", "slow"));
        String id = loop.session().tasks().open().getFirst().id();
        loop.feed(null, new TaskEnded(id, TaskOutcome.COMPLETED, "one done"));
        assertTrue(resultsSeen(loop).isEmpty());

        loop.feed(loop.runId(), new Cancel(new RequestId("stop")));

        // 这一轮被撤销了；结果进了历史，下一轮看得到。名单里已经没有它
        assertEquals(EndReason.CANCELLED, ((Ended) loop.run().phase()).reason());
        assertEquals(List.of(new TaskResultBlock(id, TaskOutcome.COMPLETED, "one done")), resultsSeen(loop));
        assertTrue(loop.session().tasks().open().isEmpty());
        assertNull(loop.session().activeRun());
    }

    @Test
    void twoResultsInARowTheFirstStartsARunAndTheSecondJoinsIt() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        calls(loop, task("c1", "one"), task("c2", "two"));
        List<OpenTask> open = loop.session().tasks().open();
        done(loop, "two are out");

        Decision first = loop.feed(null, new TaskEnded(open.get(0).id(), TaskOutcome.COMPLETED, "one done"));
        RunId run = loop.session().activeRun();
        Decision second = loop.feed(null, new TaskEnded(open.get(1).id(), TaskOutcome.FAILED, "two broke"));

        assertEquals(1, first.commands().stream().filter(AssembleContext.class::isInstance).count());
        assertTrue(second.commands().stream().noneMatch(AssembleContext.class::isInstance));
        assertEquals(run, loop.session().activeRun());
        // 自动开的只有一轮：加入的那一个不算
        assertEquals(1, loop.session().tasks().unattendedRuns());
        assertTrue(loop.session().tasks().open().isEmpty());
        finishAutoRun(loop, first);
        assertEquals(2, resultsSeen(loop).size());
    }

    // ---- 无人说话的计数 ----

    @Test
    void theCountOfUnattendedRunsStartsOverOnEveryKindOfHumanInput() {
        for (String kind : List.of("prompt", "follow-up", "steer", "answer")) {
            LoopFixture loop = new LoopFixture();
            ask(loop, "go");
            calls(loop, task("c1", "one"), task("c2", "two"));
            List<OpenTask> open = loop.session().tasks().open();
            done(loop, "two are out");
            Decision auto = loop.feed(null, new TaskEnded(open.get(0).id(), TaskOutcome.COMPLETED, "one done"));
            assertEquals(1, loop.session().tasks().unattendedRuns(), kind);
            assertEquals(2, loop.session().tasks().unattendedTasks(), kind);
            switch (kind) {
                case "prompt" -> loop.prompt("and now?");
                case "follow-up" -> loop.feed(null, FollowUpSubmittedFixture.of(new SubmissionId("f"), new RequestId("f"), "and now?"));
                case "steer" -> loop.feed(null, SteerSubmittedFixture.of(new SubmissionId("s"), new RequestId("s"), "wait"));
                default -> {
                    AssembleContext assemble = auto.commands().stream().filter(AssembleContext.class::isInstance).map(AssembleContext.class::cast)
                            .findFirst().orElseThrow();
                    loop.feed(loop.runId(), ContextAssembledFixture.of(assemble.id(), "s", List.of(), null, List.of()));
                    calls(loop, askUser("q1"));
                    String asked = ((AwaitingAnswer) loop.run().phase()).question().id();
                    loop.feed(loop.runId(), new Answered(new SubmissionId("a"), new RequestId("a"), asked, new Answer(null, "this one")));
                }
            }
            assertEquals(0, loop.session().tasks().unattendedRuns(), kind);
            assertEquals(0, loop.session().tasks().unattendedTasks(), kind);
            // 还没回来的那个任务还在名单里：清零的只是计数
            assertEquals(1, loop.session().tasks().open().size(), kind);
        }
    }

    @Test
    void withALimitOfZeroTasksAreOffAndWithZeroUnattendedRunsNothingContinuesOnItsOwn() {
        LoopFixture off = new LoopFixture(10, new TaskLimits(0, 10, 10, null));
        ask(off, "go");
        calls(off, task("c1", "one"));
        assertTrue(resultOf(off, "c1").startsWith("ERROR Not started: 0 tasks are already running, which is the limit"));

        LoopFixture manual = new LoopFixture(10, new TaskLimits(4, 0, 10, null));
        ask(manual, "go");
        calls(manual, task("c1", "one"));
        String id = manual.session().tasks().open().getFirst().id();
        done(manual, "out");
        Decision back = manual.feed(null, new TaskEnded(id, TaskOutcome.COMPLETED, "one done"));
        assertTrue(back.commands().isEmpty());
        assertNull(manual.session().activeRun());
        assertEquals(List.of(new TaskResultBlock(id, TaskOutcome.COMPLETED, "one done")), resultsSeen(manual));
    }

    @Test
    void aQuestionTheUserQueuedDuringAnUnattendedRunRunsAfterIt() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        calls(loop, task("c1", "one"));
        String id = loop.session().tasks().open().getFirst().id();
        done(loop, "out");
        Decision auto = loop.feed(null, new TaskEnded(id, TaskOutcome.COMPLETED, "one done"));
        RunId autoRun = loop.session().activeRun();

        loop.feed(null, FollowUpSubmittedFixture.of(new SubmissionId("f"), new RequestId("f"), "what did it say?"));
        assertEquals(1, loop.session().followUps().size());
        finishAutoRun(loop, auto);

        // 自动的那一轮结束后，排队的提问照常开下一轮
        assertTrue(loop.state.runs().get(autoRun).isEnded());
        assertFalse(autoRun.equals(loop.session().activeRun()));
        assertTrue(loop.session().followUps().isEmpty());
        assertEquals(Message.user("what did it say?"), loop.session().messages().getLast());
    }

    // ---- 为任务开的会话 ----

    @Test
    void aTasksSessionTakesNoHumanInputButCanBeToldToStop() {
        LoopFixture loop = new LoopFixture();
        SessionId child = new SessionId("s-t9-0");
        loop.feed(child, null, new TaskAssigned("t9-0", LoopFixture.SESSION, "Check module A.", null, null, null, null, null, false));
        RunId run = loop.state.sessions().get(child).activeRun();
        KernelState before = loop.state;

        assertSame(before, loop.feed(child, null, FollowUpSubmittedFixture.of(new SubmissionId("f"), new RequestId("f"), "hello?")).newState());
        assertSame(before, loop.feed(child, null, SteerSubmittedFixture.of(new SubmissionId("s"), new RequestId("s"), "hello?")).newState());
        assertTrue(loop.state.sessions().get(child).followUps().isEmpty());

        loop.feed(child, null, new TaskCancelled("t9-0"));
        assertEquals(EndReason.CANCELLED, ((Ended) loop.state.runs().get(run).phase()).reason());
        // 已经停了：再说一次不改变什么
        assertSame(loop.state, loop.feed(child, null, new TaskCancelled("t9-0")).newState());
    }

    // ---- 多用户：任务的事件只在同一个人的会话之间走 ----

    private static Decision feedAs(LoopFixture loop, String user, SessionId session, tantalum.value.event.Event event) {
        tantalum.value.event.EventEnvelope envelope = new tantalum.value.event.EventEnvelope(900_000 + loop.journal.size(), LoopFixture.AT,
                new tantalum.value.id.Principal(user), session, null, event);
        return loop.kernel.step(loop.state, envelope);
    }

    @Test
    void anotherPersonCannotSettleStopOrFillSomeoneElsesTasks() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        StartTask start = calls(loop, task("c1", "Check module A.")).commands().stream().filter(StartTask.class::isInstance)
                .map(StartTask.class::cast).findFirst().orElseThrow();
        done(loop, "out");
        loop.feed(start.session(), null, new TaskAssigned(start.task(), LoopFixture.SESSION, "Check module A.", null, null, null, null, null, false));
        KernelState before = loop.state;

        // 别人名下来的“任务结束”“停任务”：不是这个会话的主人，什么都不改、什么都不发
        for (tantalum.value.event.Event event : List.of(new TaskEnded(start.task(), TaskOutcome.COMPLETED, "forged result"),
                new TaskStopRequested(new RequestId("r"), null))) {
            Decision forged = feedAs(loop, "mallory", LoopFixture.SESSION, event);
            assertSame(before, forged.newState(), event.type());
            assertTrue(forged.commands().isEmpty() && forged.facts().isEmpty(), event.type());
        }
        // 别人名下来的“任务来了”送进一个已经属于这个人的会话：也不动（哪怕那个会话是空的）
        Decision intruding = feedAs(loop, "mallory", start.session(),
                new TaskAssigned("t1-0", new SessionId("theirs"), "read the owner's files", null, null, null, null, null, false));
        assertSame(before, intruding.newState());
        assertEquals(1, loop.session().tasks().open().size());
        assertTrue(resultsSeen(loop).isEmpty());

        // 主人自己的结果照常生效
        loop.feed(null, new TaskEnded(start.task(), TaskOutcome.COMPLETED, "real result"));
        assertEquals(List.of(new TaskResultBlock(start.task(), TaskOutcome.COMPLETED, "real result")), resultsSeen(loop));
    }

    @Test
    void aTasksSessionAndItsRunBelongToThePersonWhoStartedTheTask() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        StartTask start = calls(loop, task("c1", "Check module A.")).commands().stream().filter(StartTask.class::isInstance)
                .map(StartTask.class::cast).findFirst().orElseThrow();
        assertEquals(LoopFixture.USER, start.run().principal());

        loop.feed(start.session(), null, new TaskAssigned(start.task(), LoopFixture.SESSION, "Check module A.", null, null, null, null, null, false));

        assertEquals(LoopFixture.USER, loop.state.sessions().get(start.session()).principal());
        RunId run = loop.state.sessions().get(start.session()).activeRun();
        assertEquals(LoopFixture.USER, loop.state.runs().get(run).ref().principal());
    }

    @Test
    void askingToStopATaskThatIsNotOutChangesNothing() {
        LoopFixture loop = new LoopFixture();
        // 会话还不存在
        assertSame(loop.state, loop.feed(null, new TaskStopRequested(new RequestId("r"), null)).newState());
        ask(loop, "go");
        calls(loop, task("c1", "one"));
        done(loop, "out");
        KernelState before = loop.state;

        Decision unknown = loop.feed(null, new TaskStopRequested(new RequestId("r"), "t999-0"));

        assertSame(before, unknown.newState());
        assertTrue(unknown.commands().isEmpty());
        assertSame(before, loop.feed(null, new TaskEnded("t999-0", TaskOutcome.COMPLETED, "who?")).newState());
    }
}
