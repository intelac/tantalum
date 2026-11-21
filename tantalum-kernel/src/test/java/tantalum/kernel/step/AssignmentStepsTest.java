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
import tantalum.value.event.Report;
import tantalum.value.event.Root;
import tantalum.value.state.RunCause;
import tantalum.value.state.SessionKind;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.kernel.step.AssignmentViews.ReturnReport;
import tantalum.value.command.DoWork;
import tantalum.value.command.AssembleContext;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookResult;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.hook.HookContext;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionState;
import tantalum.value.command.RunTool;
import tantalum.kernel.step.AssignmentViews.Assign;
import tantalum.value.command.StartTimer;
import tantalum.kernel.step.AssignmentViews.StopAssignment;
import tantalum.value.decision.Decision;
import tantalum.value.decision.ReportSettled;
import tantalum.value.decision.AssignmentStarted;
import tantalum.value.event.Cancel;
import tantalum.value.event.ContextAssembledFixture;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.RunInterrupted;
import tantalum.value.event.StopRequested;
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
import tantalum.value.model.ReportBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.Ended;
import tantalum.value.state.RunState;
import tantalum.value.assignment.OpenAssignment;
import tantalum.value.assignment.AssignmentLimits;
import tantalum.value.assignment.AssignmentOutcome;
import tantalum.value.assignment.AssignmentTools;

/** 交代（subagent 需求 1–6；session-model 需求 6）：放出时不停、结果作为一条输入回来、每次交代恰好一个结果、上限、停止。 */
class AssignmentStepsTest {

    private static ToolCallBlock task(String callId, String prompt) {
        return AssignmentFixture.task(callId, prompt);
    }

    private static ToolCallBlock stop(String callId, String id) {
        return AssignmentFixture.stop(callId, id);
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
        List<T> found = AssignmentFixture.commands(decision).stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, found.size(), "expected one " + type.getSimpleName() + " in " + AssignmentFixture.commands(decision));
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
    private static OpenAssignment startOne(LoopFixture loop) {
        ask(loop, "look into the three modules");
        calls(loop, task("c1", "Check module A.\nReport what you find."));
        OpenAssignment open = loop.session().assignments().open().getLast();
        done(loop, "started");
        return open;
    }

    /** 让任务在它自己的会话里开始，走到模型被调用。 */
    private static RunId assign(LoopFixture loop, Assign start) {
        AssignmentFixture.deliver(loop, start);
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
        assertTrue(AssignmentFixture.commands(decision).stream().noneMatch(RunTool.class::isInstance));
        Assign start = only(decision, Assign.class);
        assertEquals("Check module A.\nReport what you find.", start.prompt());
        assertEquals(new SessionId("s-" + start.assignment()), start.session());
        OpenAssignment open = loop.session().assignments().open().getFirst();
        assertEquals(start.assignment(), open.id());
        assertEquals("Check module A.", open.brief());
        // 默认不设期限：不起定时器
        assertNull(open.deadline());
        assertNull(open.timer());
        assertTrue(AssignmentFixture.commands(decision).stream().noneMatch(StartTimer.class::isInstance));
        assertTrue(resultOf(loop, "c1").startsWith("Started task " + start.assignment()));
        assertTrue(decision.facts().stream().anyMatch(fact -> fact instanceof AssignmentStarted started && started.assignment().equals(start.assignment())));

        // 这一轮照常结束，会话空闲，名单还在
        done(loop, "started");
        assertTrue(loop.run().isEnded());
        assertNull(loop.session().activeRun());
        assertEquals(1, loop.session().assignments().open().size());
    }

    /** 任务用放出它的那一轮的模型：不带上的话任务会落到默认路线上（2026-11-18 真模型测试发现，任务跑在了本机的小模型上）。 */
    @Test
    void aTaskRunsOnTheModelOfTheRunThatStartedIt() {
        LoopFixture loop = new LoopFixture();
        tantalum.value.model.ModelChoice sonnet = new tantalum.value.model.ModelChoice("claude", "claude-sonnet-5-5", "medium");
        loop.prompt("look into module A");
        loop.feed(loop.runId(), new tantalum.value.event.ContextAssembled(loop.onlyCommandId(), "s", List.of(), null, List.of(), null, null,
                Map.of(), sonnet, null, null));

        Assign start = only(calls(loop, task("c1", "Check module A.")), Assign.class);

        assertEquals(sonnet, start.model());
    }

    @Test
    void theUserCanGoOnTalkingWhileATaskIsOut() {
        LoopFixture loop = new LoopFixture();
        startOne(loop);

        ask(loop, "meanwhile, what time is it?");
        done(loop, "noon");

        assertTrue(loop.run().isEnded());
        assertEquals(1, loop.session().assignments().open().size());
    }

    @Test
    void aTaskIsRefusedWithAReasonAndNothingIsStarted() {
        LoopFixture loop = new LoopFixture(10, new AssignmentLimits(1, 10, 10, null));
        ask(loop, "go");
        Decision decision = calls(loop, task("c1", "  "), task("c2", "first"), task("c3", "second"));

        assertTrue(resultOf(loop, "c1").startsWith("ERROR task needs a non-empty \"prompt\""));
        assertTrue(resultOf(loop, "c2").startsWith("Started task"));
        assertTrue(resultOf(loop, "c3").startsWith("ERROR Not started: 1 tasks are already running"));
        assertEquals(1, AssignmentFixture.commands(decision).stream().filter(Assign.class::isInstance).count());
        assertEquals(1, loop.session().assignments().open().size());
    }

    @Test
    void tasksStartedSinceTheUserLastSpokeAreCountedAndTheCountStartsOverWhenTheySpeak() {
        LoopFixture loop = new LoopFixture(10, new AssignmentLimits(10, 10, 2, null));
        ask(loop, "go");
        calls(loop, task("c1", "one"), task("c2", "two"), task("c3", "three"));

        assertTrue(resultOf(loop, "c3").startsWith("ERROR Not started: 2 tasks have been started since the user last spoke"));
        assertEquals(2, loop.session().assignments().unattendedAssignments());

        done(loop, "two are out");
        ask(loop, "go on");
        assertEquals(0, loop.session().assignments().unattendedAssignments());
        calls(loop, task("c4", "three"));
        assertTrue(resultOf(loop, "c4").startsWith("Started task"));
    }

    @Test
    void aTaskArrivesInASessionOfItsOwnAndThatSessionTakesNothingElse() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        Assign start = only(calls(loop, task("c1", "Check module A.")), Assign.class);

        RunId run = assign(loop, start);
        CommandId call = loop.onlyCommandId();

        SessionState child = loop.state.sessions().get(start.session());
        assertEquals(SessionKind.HELPER, child.kind());
        assertEquals(LoopFixture.SESSION, child.helper().issuer());
        assertEquals(start.assignment(), child.helper().assignment());
        // 为这次交代开的那一轮欠着它的结果；追到头是你引起的
        assertEquals(new RunCause(RunCause.StartedBy.ASSIGNMENT, Root.USER, start.assignment()), loop.state.runs().get(run).cause());
        assertEquals(ModelSteps.AWAITING_MODEL, loop.state.runs().get(run).phase().name());
        // 交代进历史时是它自己的内容块，不是一条普通的用户消息（session-model 需求 4.1）
        assertEquals(List.of(Message.assignment(start.assignment(), "Check module A.")), child.messages());
        assertFalse(child.messages().getFirst().fromUser());

        // 重复送达、人的提问：都不改它
        assertSame(loop.state, loop.feed(start.session(), null, AssignmentFixture.assignmentOf(start)).newState());
        assertSame(loop.state, loop.feed(start.session(), null, UserPromptFixture.of(new SubmissionId("x"), new RequestId("x"), "hello?")).newState());

        // 它自己不能再放任务
        Decision nested = reply(loop, start.session(), run, call, StopReason.TOOL_USE, task("n1", "go deeper"));
        assertTrue(AssignmentFixture.commands(nested).stream().noneMatch(Assign.class::isInstance));
        assertTrue(loop.state.sessions().get(start.session()).assignments().open().isEmpty());
        ToolResultBlock refused = (ToolResultBlock) loop.state.sessions().get(start.session()).messages().getLast().content().getFirst();
        assertTrue(refused.error());
        assertEquals("A task cannot start tasks. Do this work yourself.", refused.text());
    }

    @Test
    void theTaskHandsBackItsLastAnswerWhenItsRunEnds() {
        LoopFixture loop = new LoopFixture();
        OpenAssignment open = startOne(loop);
        Assign start = (Assign) AssignmentFixture.views(loop.commands).stream().filter(Assign.class::isInstance).findFirst().orElseThrow();
        RunId run = assign(loop, start);

        Decision ended = reply(loop, start.session(), run, loop.onlyCommandId(), StopReason.END_TURN, LoopFixture.text("Module A is fine.").getFirst());

        ReturnReport back = only(ended, ReturnReport.class);
        assertEquals(LoopFixture.SESSION, back.issuer());
        assertEquals(open.id(), back.assignment());
        assertEquals(AssignmentOutcome.COMPLETED, back.outcome());
        assertEquals("Module A is fine.", back.text());
        assertTrue(loop.state.runs().get(run).phase() instanceof Ended);
    }

    /** 结束前的钩子让任务改一处（比如引用核对）：任务于是答了两次。交回的是这一轮的每一次回答，不能只剩那句更正。 */
    @Test
    void whenAHookMadeTheTaskAnswerAgainEveryAnswerIsHandedBack() {
        KernelHook check = new KernelHook() {
            @Override public String name() { return "check"; }
            @Override public HookResult beforeRunEnd(KernelState state, HookContext context) {
                SessionState session = state.sessions().get(context.run().session());
                boolean task = session.kind() == SessionKind.HELPER;
                long answers = session.messages().stream().filter(m -> m.role() == tantalum.value.model.Role.ASSISTANT).count();
                return task && answers == 1 && context.work() == null ? new NeedWork("check", new Json.Null()) : Allow.PLAIN;
            }
        };
        LoopFixture loop = new LoopFixture(check);
        startOne(loop);
        Assign start = (Assign) AssignmentFixture.views(loop.commands).stream().filter(Assign.class::isInstance).findFirst().orElseThrow();
        RunId run = assign(loop, start);

        Decision first = reply(loop, start.session(), run, loop.onlyCommandId(), StopReason.END_TURN,
                LoopFixture.text("Module A is fine, see a.py:9.").getFirst());
        DoWork work = only(first, DoWork.class);
        assertTrue(AssignmentFixture.commands(first).stream().noneMatch(ReturnReport.class::isInstance), "the task has not ended yet");
        Decision again = loop.feed(start.session(), run, new WorkDone(work.id(), "check", new Json.Null(), false, "a.py:9 was never read; fix it", null));
        if (AssignmentFixture.commands(again).getFirst() instanceof AssembleContext assemble) {
            again = loop.feed(start.session(), run, ContextAssembledFixture.of(assemble.id(), "s", List.of(), null, List.of()));
        }
        Decision ended = reply(loop, start.session(), run, modelCall(again), StopReason.END_TURN, LoopFixture.text("Correction: it is a.py:7.").getFirst());

        assertEquals("Module A is fine, see a.py:9.\n\nCorrection: it is a.py:7.", only(ended, ReturnReport.class).text());
    }

    @Test
    void howTheTasksRunEndsDecidesTheOutcome() {
        assertEquals(AssignmentOutcome.STOPPED, outcomeAfter(new Cancel(new RequestId("r"))));
        assertEquals(AssignmentOutcome.INTERRUPTED, outcomeAfter(new RunInterrupted()));
    }

    private static AssignmentOutcome outcomeAfter(tantalum.value.event.Event event) {
        LoopFixture loop = new LoopFixture();
        startOne(loop);
        Assign start = (Assign) AssignmentFixture.views(loop.commands).stream().filter(Assign.class::isInstance).findFirst().orElseThrow();
        RunId run = assign(loop, start);
        return only(loop.feed(start.session(), run, event), ReturnReport.class).outcome();
    }

    @Test
    void aResultThatArrivesWhileIdleStartsARunThatNoPersonStarted() {
        LoopFixture loop = new LoopFixture();
        OpenAssignment open = startOne(loop);
        RunId before = loop.session().lastRun();

        Decision decision = loop.feed(null, AssignmentFixture.report(open.id(), AssignmentOutcome.COMPLETED, "Module A is fine.", Root.USER));

        assertTrue(loop.session().assignments().open().isEmpty());
        assertFalse(loop.session().activeRun().equals(before));
        assertEquals(ContextSteps.AWAITING_CONTEXT, loop.phase());
        assertEquals(new RunCause(RunCause.StartedBy.REPORT, Root.USER, null), loop.run().cause());
        assertFalse(loop.run().cause().attended(), "nobody is waiting for this run");
        Message last = loop.session().messages().getLast();
        assertEquals(Role.USER, last.role());
        assertEquals(List.of(new ReportBlock(open.id(), AssignmentOutcome.COMPLETED, "Module A is fine.")), last.content());
        assertEquals(1, loop.session().assignments().unattendedRuns());
        assertTrue(decision.facts().stream().anyMatch(fact -> fact instanceof ReportSettled settled && !settled.paused()));

        // 同一个结果再到一次：名单里没有它了，什么都不改
        assertSame(loop.state, loop.feed(null, AssignmentFixture.report(open.id(), AssignmentOutcome.COMPLETED, "Module A is fine.", Root.USER)).newState());
    }

    @Test
    void aResultThatArrivesDuringARunJoinsThatRun() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        calls(loop, task("c1", "Check module A."), LoopFixture.call("c2", "slow"));
        OpenAssignment open = loop.session().assignments().open().getFirst();
        RunId run = loop.runId();
        RunTool slow = (RunTool) loop.commands.stream().filter(RunTool.class::isInstance).findFirst().orElseThrow();

        loop.feed(null, AssignmentFixture.report(open.id(), AssignmentOutcome.FAILED, "could not read the folder", Root.USER));

        // 没有开新的一轮；结果在这一轮下次调模型时并进历史
        assertEquals(run, loop.session().activeRun());
        assertEquals(1, loop.joining().size());
        assertEquals(0, loop.session().assignments().unattendedRuns());
        loop.feed(run, ToolFinishedFixture.of(slow.id(), "c2", "ok", false, null));
        assertEquals(List.of(new ReportBlock(open.id(), AssignmentOutcome.FAILED, "could not read the folder")),
                loop.session().messages().getLast().content());
        assertEquals(ModelSteps.AWAITING_MODEL, loop.phase());
    }

    @Test
    void afterTheLimitOfUnattendedRunsAResultIsKeptButStartsNothingUntilThePersonSpeaks() {
        LoopFixture loop = new LoopFixture(10, new AssignmentLimits(4, 1, 10, null));
        ask(loop, "go");
        calls(loop, task("c1", "one"), task("c2", "two"));
        List<OpenAssignment> open = loop.session().assignments().open();
        done(loop, "two are out");

        Decision first = loop.feed(null, AssignmentFixture.report(open.get(0).id(), AssignmentOutcome.COMPLETED, "one done", Root.USER));
        loop.feed(loop.runId(), ContextAssembledFixture.of(only(first, tantalum.value.command.AssembleContext.class).id(), "s", List.of(), null, List.of()));
        done(loop, "noted");
        RunId last = loop.session().lastRun();

        Decision paused = loop.feed(null, AssignmentFixture.report(open.get(1).id(), AssignmentOutcome.COMPLETED, "two done", Root.USER));

        assertNull(loop.session().activeRun());
        assertEquals(last, loop.session().lastRun());
        assertTrue(AssignmentFixture.commands(paused).isEmpty());
        assertEquals(List.of(new ReportBlock(open.get(1).id(), AssignmentOutcome.COMPLETED, "two done")),
                loop.session().messages().getLast().content());
        assertTrue(paused.facts().stream().anyMatch(fact -> fact instanceof ReportSettled settled && settled.paused()));
        assertTrue(loop.session().assignments().open().isEmpty());

        ask(loop, "go on");
        assertEquals(0, loop.session().assignments().unattendedRuns());
    }

    @Test
    void whenADeadlineIsSetItEndsATaskThatHasNotComeBackAndALaterResultChangesNothing() {
        LoopFixture loop = new LoopFixture(10, new AssignmentLimits(4, 10, 10, Duration.ofMinutes(30)));
        ask(loop, "look into the three modules");
        Decision started = calls(loop, task("c1", "Check module A.\nReport what you find."));
        OpenAssignment open = loop.session().assignments().open().getLast();
        assertEquals(LoopFixture.AT.plus(Duration.ofMinutes(30)), open.deadline());
        assertEquals(open.timer(), only(started, StartTimer.class).id());
        done(loop, "started");

        // 别的定时器：不动
        assertSame(loop.state, loop.feed(null, new TimerFired(new CommandId("someone-else"))).newState());

        Decision decision = loop.feed(null, new TimerFired(open.timer()));

        assertTrue(loop.session().assignments().open().isEmpty());
        // 期限到了也送一条叫停，排在交代后面
        assertTrue(loop.session().outbox().stream().anyMatch(each -> each.id().equals("stop:" + open.id()) && each.to().equals(open.session())));
        assertEquals(List.of(new ReportBlock(open.id(), AssignmentOutcome.TIMED_OUT, null)), loop.session().messages().getLast().content());

        assertSame(loop.state, loop.feed(null, AssignmentFixture.report(open.id(), AssignmentOutcome.STOPPED, null, Root.USER)).newState());
        assertSame(loop.state, loop.feed(null, new TimerFired(open.timer())).newState());
    }

    @Test
    void aResultTellsTheDeadlineTimerToStop() {
        LoopFixture loop = new LoopFixture(10, new AssignmentLimits(4, 10, 10, Duration.ofMinutes(30)));
        OpenAssignment open = startOne(loop);

        Decision ended = loop.feed(null, AssignmentFixture.report(open.id(), AssignmentOutcome.COMPLETED, "done", Root.USER));

        assertEquals(java.util.Set.of(open.timer()), only(ended, tantalum.value.command.AbortCommands.class).commands());
    }

    @Test
    void theOneWhoStartedATaskCanCallItOff() {
        LoopFixture loop = new LoopFixture();
        startOne(loop);
        Assign start = (Assign) AssignmentFixture.views(loop.commands).stream().filter(Assign.class::isInstance).findFirst().orElseThrow();
        RunId run = assign(loop, start);

        Decision stopped = loop.feed(start.session(), null, AssignmentFixture.cancelled(start.assignment()));

        assertTrue(loop.state.runs().get(run).isEnded());
        assertEquals(AssignmentOutcome.STOPPED, only(stopped, ReturnReport.class).outcome());
    }

    @Test
    void theModelAndTheUserCanStopATaskAndItsResultStillArrives() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        calls(loop, task("c1", "one"), task("c2", "two"));
        List<OpenAssignment> open = loop.session().assignments().open();

        done(loop, "two are out");
        // 第二个已经送到（帮手确认了）；第一个还没确认
        loop.feed(null, new tantalum.value.event.Delivered("assign:" + open.get(1).id()));
        ask(loop, "stop them");
        Decision byModel = calls(loop, stop("c3", open.get(0).id()), stop("c4", "nope"));
        assertTrue(resultOf(loop, "c3").startsWith("Stop requested for task " + open.get(0).id()));
        assertEquals("ERROR No running task \"nope\".", resultOf(loop, "c4"));
        // 没确认送到的（delivery 审查）：撤掉、当场记成“已停止”，叫停照样送出（送到了只是确认丢了的，帮手会停下）
        assertTrue(loop.session().outbox().stream().noneMatch(each -> each.id().equals("assign:" + open.get(0).id())));
        assertEquals(open.get(0).session(), only(byModel, StopAssignment.class).session());
        assertEquals(List.of(open.get(1).id()), loop.session().assignments().open().stream().map(OpenAssignment::id).toList());

        done(loop, "stopping one");
        loop.feed(null, new StopRequested(new RequestId("r"), null));
        // 送到了的那个：名单不动，叫停送过去，等它自己交回“已停止”
        assertTrue(loop.session().outbox().stream().anyMatch(each -> each.id().equals("stop:" + open.get(1).id()) && each.ackTimer() != null));
        assertEquals(1, loop.session().assignments().open().size());
        loop.feed(null, AssignmentFixture.report(open.get(1).id(), AssignmentOutcome.STOPPED, null, Root.USER));
        assertTrue(loop.session().assignments().open().isEmpty());
        // 第一个的帮手要是其实收到了（只是确认丢了），它交回的“已停止”按名单去重，什么都不改
        tantalum.value.state.KernelState before = loop.state;
        loop.feed(null, AssignmentFixture.report(open.get(0).id(), AssignmentOutcome.STOPPED, null, Root.USER));
        assertSame(before, loop.state);
    }

    @Test
    void stoppingTheMainConversationLeavesItsTasksRunning() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        calls(loop, task("c1", "one"));

        Decision cancelled = loop.feed(loop.runId(), new Cancel(new RequestId("r")));

        assertTrue(loop.run().isEnded());
        assertTrue(AssignmentFixture.commands(cancelled).stream().noneMatch(StopAssignment.class::isInstance));
        assertEquals(1, loop.session().assignments().open().size());
    }

    @Test
    void feedingTheSameEventsAgainGivesTheSameStateAndCommands() {
        LoopFixture loop = new LoopFixture();
        OpenAssignment open = startOne(loop);
        Assign start = (Assign) AssignmentFixture.views(loop.commands).stream().filter(Assign.class::isInstance).findFirst().orElseThrow();
        RunId run = assign(loop, start);
        reply(loop, start.session(), run, loop.onlyCommandId(), StopReason.END_TURN, LoopFixture.text("done").getFirst());
        loop.feed(null, AssignmentFixture.report(open.id(), AssignmentOutcome.COMPLETED, "done", Root.USER));
        loop.feed(null, new TimerFired(new CommandId("late")));

        Replay.Result again = Replay.run(loop.kernel, loop.journal);

        assertEquals(loop.state, again.state());
        List<Command> commands = again.commands();
        assertEquals(loop.commands, commands);
    }

    @Test
    void aFailedModelCallInTheTaskComesBackAsFailed() {
        LoopFixture loop = new LoopFixture();
        startOne(loop);
        Assign start = (Assign) AssignmentFixture.views(loop.commands).stream().filter(Assign.class::isInstance).findFirst().orElseThrow();
        RunId run = assign(loop, start);

        Decision failed = loop.feed(start.session(), run,
                new ModelFailed(loop.onlyCommandId(), tantalum.value.model.FailureReason.REFUSAL, false, null, null, "refused", null, null, null));

        assertEquals(AssignmentOutcome.FAILED, only(failed, ReturnReport.class).outcome());
    }
}
