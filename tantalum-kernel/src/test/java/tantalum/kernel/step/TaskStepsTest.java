package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.Replay;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.ReturnTask;
import tantalum.value.command.RunTool;
import tantalum.value.command.StartTask;
import tantalum.value.command.StartTimer;
import tantalum.value.command.StopTask;
import tantalum.value.decision.Decision;
import tantalum.value.decision.TaskSettled;
import tantalum.value.decision.TaskStarted;
import tantalum.value.event.Cancel;
import tantalum.value.event.ContextAssembledFixture;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.RunInterrupted;
import tantalum.value.event.TaskAssigned;
import tantalum.value.event.TaskEnded;
import tantalum.value.event.TaskStopRequested;
import tantalum.value.event.TimerFired;
import tantalum.value.event.ToolFinishedFixture;
import tantalum.value.event.UserPromptFixture;
import tantalum.value.id.CommandId;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TaskResultBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.Ended;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tantalum.value.task.OpenTask;
import tantalum.value.task.TaskLimits;
import tantalum.value.task.TaskOutcome;
import tantalum.value.task.TaskTools;

/** 任务（subagent 需求 1–6）：放出时不停、结果作为事件回来、每个任务恰好一个结果、上限、停止。 */
class TaskStepsTest {

    private static ToolCallBlock task(String callId, String prompt) {
        return new ToolCallBlock(callId, TaskTools.TASK, "{}", new Json.Obj(Map.of(TaskTools.PROMPT, new Json.Str(prompt))));
    }

    private static ToolCallBlock stop(String callId, String id) {
        return new ToolCallBlock(callId, TaskTools.TASK_STOP, "{}", new Json.Obj(Map.of(TaskTools.ID, new Json.Str(id))));
    }

    /** 用户说一句话，走到模型被调用。 */
    private static void ask(LoopFixture loop, String text) {
        loop.prompt(text);
        loop.feed(loop.runId(), ContextAssembledFixture.of(loop.onlyCommandId(), "s", List.of(), null, List.of()));
    }

    private static CommandId modelCall(Decision decision) {
        return decision.commands().stream().filter(CallModel.class::isInstance).findFirst().orElseThrow().id();
    }

    private static Decision reply(LoopFixture loop, SessionId session, RunId run, CommandId call, StopReason stop, ContentBlock... content) {
        return loop.feed(session, run, new ModelReplied(call, List.of(content), stop, "m", null, null, null, null));
    }

    /** 模型这一次回复里调用了这些工具。 */
    private static Decision calls(LoopFixture loop, ContentBlock... calls) {
        return reply(loop, LoopFixture.SESSION, loop.runId(), modelCall(loop.last), StopReason.TOOL_USE, calls);
    }

    /** 模型说完了，这一轮结束。 */
    private static Decision done(LoopFixture loop, String text) {
        return reply(loop, LoopFixture.SESSION, loop.runId(), modelCall(loop.last), StopReason.END_TURN, LoopFixture.text(text).getFirst());
    }

    private static <T> T only(Decision decision, Class<T> type) {
        List<T> found = decision.commands().stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, found.size(), "expected one " + type.getSimpleName() + " in " + decision.commands());
        return found.getFirst();
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

    /** 主会话放出一个任务，并让这一轮说完结束。返回名单里的那一条。 */
    private static OpenTask startOne(LoopFixture loop) {
        ask(loop, "look into the three modules");
        calls(loop, task("c1", "Check module A.\nReport what you find."));
        OpenTask open = loop.session().tasks().open().getLast();
        done(loop, "started");
        return open;
    }

    /** 让任务在它自己的会话里开始，走到模型被调用。 */
    private static RunId assign(LoopFixture loop, StartTask start) {
        loop.feed(start.session(), null, new TaskAssigned(start.task(), LoopFixture.SESSION, start.prompt(), null, null, null, null, null, false));
        RunId run = loop.state.sessions().get(start.session()).activeRun();
        loop.feed(start.session(), run, ContextAssembledFixture.of(loop.onlyCommandId(), "s", List.of(), null, List.of()));
        return run;
    }

    @Test
    void aTaskIsStartedWithoutTheRunStoppingForIt() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "look into module A");

        Decision decision = calls(loop, task("c1", "Check module A.\nReport what you find."));

        // 当场有结果，同一步里就接着调模型：没有新的等待状态，没有 RunTool
        assertEquals(ModelSteps.AWAITING_MODEL, loop.phase());
        assertTrue(decision.commands().stream().noneMatch(RunTool.class::isInstance));
        StartTask start = only(decision, StartTask.class);
        assertEquals("Check module A.\nReport what you find.", start.prompt());
        assertEquals(new SessionId("s-" + start.task()), start.session());
        OpenTask open = loop.session().tasks().open().getFirst();
        assertEquals(start.task(), open.id());
        assertEquals("Check module A.", open.brief());
        assertEquals(LoopFixture.AT.plus(TaskLimits.DEFAULT.deadline()), open.deadline());
        assertEquals(open.timer(), only(decision, StartTimer.class).id());
        assertTrue(resultOf(loop, "c1").startsWith("Started task " + start.task()));
        assertTrue(decision.facts().stream().anyMatch(fact -> fact instanceof TaskStarted started && started.task().equals(start.task())));

        // 这一轮照常结束，会话空闲，名单还在
        done(loop, "started");
        assertTrue(loop.run().isEnded());
        assertNull(loop.session().activeRun());
        assertEquals(1, loop.session().tasks().open().size());
    }

    @Test
    void theUserCanGoOnTalkingWhileATaskIsOut() {
        LoopFixture loop = new LoopFixture();
        startOne(loop);

        ask(loop, "meanwhile, what time is it?");
        done(loop, "noon");

        assertTrue(loop.run().isEnded());
        assertEquals(1, loop.session().tasks().open().size());
    }

    @Test
    void aTaskIsRefusedWithAReasonAndNothingIsStarted() {
        LoopFixture loop = new LoopFixture(10, new TaskLimits(1, 10, 10, Duration.ofMinutes(30)));
        ask(loop, "go");
        Decision decision = calls(loop, task("c1", "  "), task("c2", "first"), task("c3", "second"));

        assertTrue(resultOf(loop, "c1").startsWith("ERROR task needs a non-empty \"prompt\""));
        assertTrue(resultOf(loop, "c2").startsWith("Started task"));
        assertTrue(resultOf(loop, "c3").startsWith("ERROR Not started: 1 tasks are already running"));
        assertEquals(1, decision.commands().stream().filter(StartTask.class::isInstance).count());
        assertEquals(1, loop.session().tasks().open().size());
    }

    @Test
    void tasksStartedSinceTheUserLastSpokeAreCountedAndTheCountStartsOverWhenTheySpeak() {
        LoopFixture loop = new LoopFixture(10, new TaskLimits(10, 10, 2, Duration.ofMinutes(30)));
        ask(loop, "go");
        calls(loop, task("c1", "one"), task("c2", "two"), task("c3", "three"));

        assertTrue(resultOf(loop, "c3").startsWith("ERROR Not started: 2 tasks have been started since the user last spoke"));
        assertEquals(2, loop.session().tasks().unattendedTasks());

        done(loop, "two are out");
        ask(loop, "go on");
        assertEquals(0, loop.session().tasks().unattendedTasks());
        calls(loop, task("c4", "three"));
        assertTrue(resultOf(loop, "c4").startsWith("Started task"));
    }

    @Test
    void aTaskArrivesInASessionOfItsOwnAndThatSessionTakesNothingElse() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        StartTask start = only(calls(loop, task("c1", "Check module A.")), StartTask.class);

        RunId run = assign(loop, start);
        CommandId call = loop.onlyCommandId();

        SessionState child = loop.state.sessions().get(start.session());
        assertEquals(LoopFixture.SESSION, child.tasks().origin().parent());
        assertEquals(start.task(), child.tasks().origin().task());
        assertEquals(new OwnerRef.OfTask(LoopFixture.SESSION, start.task()), loop.state.runs().get(run).owner());
        assertEquals(ModelSteps.AWAITING_MODEL, loop.state.runs().get(run).phase().name());
        assertEquals(List.of(Message.user("Check module A.")), child.messages());

        // 重复送达、人的提问：都不改它
        assertSame(loop.state, loop.feed(start.session(), null,
                new TaskAssigned(start.task(), LoopFixture.SESSION, "again", null, null, null, null, null, false)).newState());
        assertSame(loop.state, loop.feed(start.session(), null, UserPromptFixture.of(new SubmissionId("x"), new RequestId("x"),
                new OwnerRef.OfSession(start.session()), "hello?")).newState());

        // 它自己不能再放任务
        Decision nested = reply(loop, start.session(), run, call, StopReason.TOOL_USE, task("n1", "go deeper"));
        assertTrue(nested.commands().stream().noneMatch(StartTask.class::isInstance));
        assertTrue(loop.state.sessions().get(start.session()).tasks().open().isEmpty());
    }

    @Test
    void theTaskHandsBackItsLastAnswerWhenItsRunEnds() {
        LoopFixture loop = new LoopFixture();
        OpenTask open = startOne(loop);
        StartTask start = (StartTask) loop.commands.stream().filter(StartTask.class::isInstance).findFirst().orElseThrow();
        RunId run = assign(loop, start);

        Decision ended = reply(loop, start.session(), run, loop.onlyCommandId(), StopReason.END_TURN, LoopFixture.text("Module A is fine.").getFirst());

        ReturnTask back = only(ended, ReturnTask.class);
        assertEquals(LoopFixture.SESSION, back.parent());
        assertEquals(open.id(), back.task());
        assertEquals(TaskOutcome.COMPLETED, back.outcome());
        assertEquals("Module A is fine.", back.text());
        assertTrue(loop.state.runs().get(run).phase() instanceof Ended);
    }

    @Test
    void howTheTasksRunEndsDecidesTheOutcome() {
        assertEquals(TaskOutcome.STOPPED, outcomeAfter(new Cancel(new RequestId("r"))));
        assertEquals(TaskOutcome.INTERRUPTED, outcomeAfter(new RunInterrupted()));
    }

    private static TaskOutcome outcomeAfter(tantalum.value.event.Event event) {
        LoopFixture loop = new LoopFixture();
        startOne(loop);
        StartTask start = (StartTask) loop.commands.stream().filter(StartTask.class::isInstance).findFirst().orElseThrow();
        RunId run = assign(loop, start);
        return only(loop.feed(start.session(), run, event), ReturnTask.class).outcome();
    }

    @Test
    void aResultThatArrivesWhileIdleStartsARunThatNoPersonStarted() {
        LoopFixture loop = new LoopFixture();
        OpenTask open = startOne(loop);
        RunId before = loop.session().lastRun();

        Decision decision = loop.feed(null, new TaskEnded(open.id(), TaskOutcome.COMPLETED, "Module A is fine."));

        assertTrue(loop.session().tasks().open().isEmpty());
        assertFalse(loop.session().activeRun().equals(before));
        assertEquals(ContextSteps.AWAITING_CONTEXT, loop.phase());
        assertEquals(new OwnerRef.OfSession(LoopFixture.SESSION), loop.run().owner());
        Message last = loop.session().messages().getLast();
        assertEquals(Role.USER, last.role());
        assertEquals(List.of(new TaskResultBlock(open.id(), TaskOutcome.COMPLETED, "Module A is fine.")), last.content());
        assertEquals(1, loop.session().tasks().unattendedRuns());
        assertTrue(decision.facts().stream().anyMatch(fact -> fact instanceof TaskSettled settled && !settled.paused()));

        // 同一个结果再到一次：名单里没有它了，什么都不改
        assertSame(loop.state, loop.feed(null, new TaskEnded(open.id(), TaskOutcome.COMPLETED, "Module A is fine.")).newState());
    }

    @Test
    void aResultThatArrivesDuringARunJoinsThatRun() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        calls(loop, task("c1", "Check module A."), LoopFixture.call("c2", "slow"));
        OpenTask open = loop.session().tasks().open().getFirst();
        RunId run = loop.runId();
        RunTool slow = (RunTool) loop.commands.stream().filter(RunTool.class::isInstance).findFirst().orElseThrow();

        loop.feed(null, new TaskEnded(open.id(), TaskOutcome.FAILED, "could not read the folder"));

        // 没有开新的一轮；结果在这一轮下次调模型时并进历史
        assertEquals(run, loop.session().activeRun());
        assertEquals(1, loop.run().pendingSteers().size());
        assertEquals(0, loop.session().tasks().unattendedRuns());
        loop.feed(run, ToolFinishedFixture.of(slow.id(), "c2", "ok", false, null));
        assertEquals(List.of(new TaskResultBlock(open.id(), TaskOutcome.FAILED, "could not read the folder")),
                loop.session().messages().getLast().content());
        assertEquals(ModelSteps.AWAITING_MODEL, loop.phase());
    }

    @Test
    void afterTheLimitOfUnattendedRunsAResultIsKeptButStartsNothingUntilThePersonSpeaks() {
        LoopFixture loop = new LoopFixture(10, new TaskLimits(4, 1, 10, Duration.ofMinutes(30)));
        ask(loop, "go");
        calls(loop, task("c1", "one"), task("c2", "two"));
        List<OpenTask> open = loop.session().tasks().open();
        done(loop, "two are out");

        Decision first = loop.feed(null, new TaskEnded(open.get(0).id(), TaskOutcome.COMPLETED, "one done"));
        loop.feed(loop.runId(), ContextAssembledFixture.of(only(first, tantalum.value.command.AssembleContext.class).id(), "s", List.of(), null, List.of()));
        done(loop, "noted");
        RunId last = loop.session().lastRun();

        Decision paused = loop.feed(null, new TaskEnded(open.get(1).id(), TaskOutcome.COMPLETED, "two done"));

        assertNull(loop.session().activeRun());
        assertEquals(last, loop.session().lastRun());
        // 没有开运行：唯一的命令是撤掉这个任务期限的定时器
        assertEquals(List.of(open.get(1).timer()), paused.commands().stream()
                .flatMap(command -> ((tantalum.value.command.AbortCommands) command).commands().stream()).toList());
        assertEquals(List.of(new TaskResultBlock(open.get(1).id(), TaskOutcome.COMPLETED, "two done")),
                loop.session().messages().getLast().content());
        assertTrue(paused.facts().stream().anyMatch(fact -> fact instanceof TaskSettled settled && settled.paused()));
        assertTrue(loop.session().tasks().open().isEmpty());

        ask(loop, "go on");
        assertEquals(0, loop.session().tasks().unattendedRuns());
    }

    @Test
    void theDeadlineEndsATaskThatHasNotComeBackAndALaterResultChangesNothing() {
        LoopFixture loop = new LoopFixture();
        OpenTask open = startOne(loop);

        // 别的定时器：不动
        assertSame(loop.state, loop.feed(null, new TimerFired(new CommandId("someone-else"))).newState());

        Decision decision = loop.feed(null, new TimerFired(open.timer()));

        assertTrue(loop.session().tasks().open().isEmpty());
        StopTask stopping = only(decision, StopTask.class);
        assertEquals(open.session(), stopping.session());
        assertEquals(List.of(new TaskResultBlock(open.id(), TaskOutcome.TIMED_OUT, null)), loop.session().messages().getLast().content());

        assertSame(loop.state, loop.feed(null, new TaskEnded(open.id(), TaskOutcome.STOPPED, null)).newState());
        assertSame(loop.state, loop.feed(null, new TimerFired(open.timer())).newState());
    }

    @Test
    void theModelAndTheUserCanStopATaskAndItsResultStillArrives() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        calls(loop, task("c1", "one"), task("c2", "two"));
        List<OpenTask> open = loop.session().tasks().open();

        Decision byModel = calls(loop, stop("c3", open.get(0).id()), stop("c4", "nope"));
        assertEquals(open.get(0).session(), only(byModel, StopTask.class).session());
        assertTrue(resultOf(loop, "c3").startsWith("Stop requested for task " + open.get(0).id()));
        assertEquals("ERROR No running task \"nope\".", resultOf(loop, "c4"));
        // 名单不动：等它自己交回“已停止”
        assertEquals(2, loop.session().tasks().open().size());

        done(loop, "stopping one");
        Decision byUser = loop.feed(null, new TaskStopRequested(new RequestId("r"), null));
        assertEquals(2, byUser.commands().stream().filter(StopTask.class::isInstance).count());

        loop.feed(null, new TaskEnded(open.get(0).id(), TaskOutcome.STOPPED, null));
        assertEquals(1, loop.session().tasks().open().size());
    }

    @Test
    void stoppingTheMainConversationLeavesItsTasksRunning() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        calls(loop, task("c1", "one"));

        Decision cancelled = loop.feed(loop.runId(), new Cancel(new RequestId("r")));

        assertTrue(loop.run().isEnded());
        assertTrue(cancelled.commands().stream().noneMatch(StopTask.class::isInstance));
        assertEquals(1, loop.session().tasks().open().size());
    }

    @Test
    void feedingTheSameEventsAgainGivesTheSameStateAndCommands() {
        LoopFixture loop = new LoopFixture();
        OpenTask open = startOne(loop);
        StartTask start = (StartTask) loop.commands.stream().filter(StartTask.class::isInstance).findFirst().orElseThrow();
        RunId run = assign(loop, start);
        reply(loop, start.session(), run, loop.onlyCommandId(), StopReason.END_TURN, LoopFixture.text("done").getFirst());
        loop.feed(null, new TaskEnded(open.id(), TaskOutcome.COMPLETED, "done"));
        loop.feed(null, new TimerFired(open.timer()));

        Replay.Result again = Replay.run(loop.kernel, loop.journal);

        assertEquals(loop.state, again.state());
        List<Command> commands = again.commands();
        assertEquals(loop.commands, commands);
    }

    @Test
    void aFailedModelCallInTheTaskComesBackAsFailed() {
        LoopFixture loop = new LoopFixture();
        startOne(loop);
        StartTask start = (StartTask) loop.commands.stream().filter(StartTask.class::isInstance).findFirst().orElseThrow();
        RunId run = assign(loop, start);

        Decision failed = loop.feed(start.session(), run,
                new ModelFailed(loop.onlyCommandId(), tantalum.value.model.FailureReason.REFUSAL, false, null, null, "refused", null, null, null));

        assertEquals(TaskOutcome.FAILED, only(failed, ReturnTask.class).outcome());
    }
}
