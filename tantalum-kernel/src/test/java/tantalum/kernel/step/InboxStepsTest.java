package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.kernel.LoopFixture.text;
import static tantalum.kernel.step.AssignmentFixture.task;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.Replay;
import tantalum.value.assignment.AssignmentLimits;
import tantalum.value.assignment.AssignmentOutcome;
import tantalum.value.assignment.OpenAssignment;
import tantalum.value.command.AssembleContext;
import tantalum.kernel.step.AssignmentViews.Assign;
import tantalum.value.command.CallModel;
import tantalum.value.command.RunTool;
import tantalum.value.decision.Decision;
import tantalum.value.event.Assignment;
import tantalum.value.event.ContextAssembledFixture;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.Report;
import tantalum.value.event.Root;
import tantalum.value.event.RunInterrupted;
import tantalum.value.event.SessionCreated;
import tantalum.value.event.ToolFinishedFixture;
import tantalum.value.event.UserPrompt;
import tantalum.value.event.UserPromptFixture;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SessionIds;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Message;
import tantalum.value.model.ReportBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.InboxItem;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunCause;
import tantalum.value.state.SessionKind;
import tantalum.value.state.SessionState;

/**
 * session-model 需求 3、5.1、6.1：所有输入进同一个收件队列，由同一个函数照 {@link tantalum.kernel.policy.InputPolicy} 处理。
 * 表里“收”的那几格怎么走、“不收”的那几格什么都不改；一轮结束时没被模型看到的输入各自的去处；一轮的 {@code root} 只降不升。
 * （排队那两条取代原来的 {@code FollowUpTest}。）
 */
class InboxStepsTest {

    private static final RunCause BY_REPORT = new RunCause(RunCause.StartedBy.REPORT, Root.USER, null, null, null);

    private static UserPrompt prompt(String text) {
        return UserPromptFixture.of(new SubmissionId("p" + text), new RequestId("p" + text), text);
    }

    /** 这个会话最近一次调模型的命令。 */
    private static CommandId modelCall(LoopFixture loop) {
        for (int i = loop.commands.size() - 1; i >= 0; i--) {
            if (loop.commands.get(i) instanceof CallModel call && call.run().session().equals(LoopFixture.SESSION)) {
                return call.id();
            }
        }
        throw new AssertionError("no model call yet");
    }

    /** 最近一次请求的上下文到了：这一轮走到模型被调用。 */
    private static CallModel assembled(LoopFixture loop) {
        for (int i = loop.commands.size() - 1; i >= 0; i--) {
            if (loop.commands.get(i) instanceof AssembleContext assemble && assemble.run().session().equals(LoopFixture.SESSION)) {
                Decision decision = loop.feed(assemble.run().run(), ContextAssembledFixture.of(assemble.id(), "s", List.of(), null, List.of()));
                return AssignmentFixture.commands(decision).stream().filter(CallModel.class::isInstance).map(CallModel.class::cast).findFirst().orElseThrow();
            }
        }
        throw new AssertionError("no context was asked for");
    }

    private static void ask(LoopFixture loop, String text) {
        loop.prompt(text);
        assembled(loop);
    }

    private static Decision calls(LoopFixture loop, ContentBlock... calls) {
        return loop.feed(loop.runId(), new ModelReplied(modelCall(loop), List.of(calls), StopReason.TOOL_USE, "m", null, null, null, null));
    }

    private static Decision done(LoopFixture loop, String text) {
        return loop.feed(loop.runId(), new ModelReplied(modelCall(loop), text(text), StopReason.END_TURN, "m", null, null, null, null));
    }

    /** 模型这次调用失败、不能重试：这一轮以失败结束。 */
    private static Decision fail(LoopFixture loop) {
        return loop.feed(loop.runId(), new ModelFailed(modelCall(loop), FailureReason.REFUSAL, false, null, null, "refused", null, null, null));
    }

    private static Report report(String assignment, String text) {
        return AssignmentFixture.report(assignment, AssignmentOutcome.COMPLETED, text, Root.USER);
    }

    private static List<ContentBlock> block(String assignment, String text) {
        return List.of(new ReportBlock(assignment, AssignmentOutcome.COMPLETED, text));
    }

    private static <T> T only(Decision decision, Class<T> type) {
        List<T> found = AssignmentFixture.commands(decision).stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, found.size(), "expected one " + type.getSimpleName() + " in " + AssignmentFixture.commands(decision));
        return found.getFirst();
    }

    private static EndReason endOf(LoopFixture loop, RunId run) {
        return assertInstanceOf(Ended.class, loop.state.runs().get(run).phase()).reason();
    }

    /** 一轮在跑，放出了一个任务，模型又被调用了一次还没回。返回那次交代的编号。 */
    private static String oneOut(LoopFixture loop) {
        ask(loop, "go");
        calls(loop, task("c1", "one"));
        return loop.session().assignments().open().getFirst().id();
    }

    // ---- 排队 ----

    @Test
    void aPromptOnAnIdleSessionStartsARunAtOnce() {
        LoopFixture loop = new LoopFixture();
        Decision decision = loop.prompt("first");

        assertEquals(ContextSteps.AWAITING_CONTEXT, loop.phase());
        assertEquals(RunCause.prompt(), LoopFixture.plain(loop.run().cause()));
        assertTrue(loop.run().cause().attended());
        assertInstanceOf(AssembleContext.class, only(decision, AssembleContext.class));
        assertEquals(List.of(Message.user("first")), loop.session().messages());
        assertTrue(loop.session().inbox().isEmpty(), "what started the run is no longer waiting");

        // 上一轮结束、会话又空闲了：再说一句同样直接开一轮
        assembled(loop);
        done(loop, "x");
        loop.feed(null, prompt("next"));
        assertEquals(ContextSteps.AWAITING_CONTEXT, loop.phase());
    }

    @Test
    void aRunThatEndsTakesExactlyOneQueuedPrompt() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("first");
        RunId first = loop.runId();
        loop.feed(null, prompt("a"));
        loop.feed(null, prompt("b"));
        assertEquals(first, loop.runId());
        assertEquals(List.of(Message.user("a"), Message.user("b")), loop.queued());

        assembled(loop);
        Decision ended = done(loop, "x");

        assertNotEquals(first, loop.runId());
        assertEquals(ContextSteps.AWAITING_CONTEXT, loop.phase());
        assertInstanceOf(AssembleContext.class, only(ended, AssembleContext.class));
        assertEquals(List.of(Message.user("b")), loop.queued());
        assertEquals(Message.user("a"), loop.session().messages().getLast());
    }

    /** 你选了“并进这一轮”，可是没有一轮在跑：和普通的一句话一样开一轮。 */
    @Test
    void aPromptThatWantsToJoinStartsARunWhenNothingIsRunning() {
        LoopFixture loop = new LoopFixture();
        loop.join("first");

        assertEquals(ContextSteps.AWAITING_CONTEXT, loop.phase());
        assertEquals(RunCause.prompt(), LoopFixture.plain(loop.run().cause()));
        assertEquals(List.of(Message.user("first")), loop.session().messages());
        assertTrue(loop.session().inbox().isEmpty());
    }

    /** 需求 3.6：条目的编号是把它放进队的那个事件的序号，所以各不相同，回放时也一样。 */
    @Test
    void everyItemIsNumberedByTheEventThatQueuedIt() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("first");
        loop.feed(null, prompt("a"));
        long a = loop.journal.getLast().seq();
        loop.join("b");
        long b = loop.journal.getLast().seq();

        assertEquals(List.of(a, b), loop.session().inbox().stream().map(InboxItem::id).toList());
        assertNotEquals(a, b);
        assertEquals(List.of(false, true), loop.session().inbox().stream().map(InboxItem::join).toList());
    }

    // ---- 表里的格子，走内核 ----

    @Test
    void aSessionThatIsNotAHelperTakesNoAssignment() {
        LoopFixture loop = new LoopFixture();
        SessionId main = new SessionId("main-u");
        loop.create(main, new SessionCreated(SessionKind.MAIN, null, false, null, null));
        KernelState before = loop.state;

        for (SessionId session : List.of(LoopFixture.SESSION, main)) {
            Decision decision = loop.feed(session, null, new Assignment("t1-0", new SessionId("other"), Root.USER, "do this", null));
            assertSame(before, decision.newState(), session.value());
            assertTrue(AssignmentFixture.commands(decision).isEmpty(), session.value());
        }
    }

    @Test
    void aHelperSessionTakesOnlyTheAssignmentItWasCreatedForAndOnlyOnce() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        Assign start = only(calls(loop, task("c1", "Check module A.")), Assign.class);
        loop.create(start.session(), AssignmentFixture.helperFor(start));
        KernelState created = loop.state;

        // 不是建它的那一条（编号不对、交代的人不对）：不收
        assertSame(created, loop.feed(start.session(), null,
                new Assignment("t999-0", LoopFixture.SESSION, Root.USER, "something else", null)).newState());
        assertSame(created, loop.feed(start.session(), null,
                new Assignment(start.assignment(), new SessionId("other"), Root.USER, "something else", null)).newState());

        Decision taken = loop.feed(start.session(), null, AssignmentFixture.assignmentOf(start));
        SessionState helper = loop.state.sessions().get(start.session());
        assertEquals(List.of(Message.assignment(start.assignment(), "Check module A.")), helper.messages());
        assertTrue(helper.inbox().isEmpty());
        assertInstanceOf(AssembleContext.class, only(taken, AssembleContext.class));

        // 同一条再到：不收
        assertSame(loop.state, loop.feed(start.session(), null, AssignmentFixture.assignmentOf(start)).newState());
    }

    @Test
    void aHelperSessionTakesNoPromptAndNoReport() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        Assign start = only(calls(loop, task("c1", "Check module A.")), Assign.class);
        AssignmentFixture.deliver(loop, start);
        KernelState before = loop.state;

        for (Event event : List.of(prompt("hello?"), UserPromptFixture.join(new SubmissionId("j"), new RequestId("j"), "hello?"),
                report(start.assignment(), "it is done"), report("t999-0", "who?"))) {
            Decision decision = loop.feed(start.session(), null, event);
            assertSame(before, decision.newState(), event.type());
            assertTrue(AssignmentFixture.commands(decision).isEmpty() && decision.facts().isEmpty(), event.type());
        }
    }

    /** 多用户：别人名下来的输入，会话不是他的，什么都不改、什么都不发。 */
    @Test
    void someoneElsesInputIsLeftAlone() {
        LoopFixture loop = new LoopFixture();
        String id = oneOut(loop);
        KernelState before = loop.state;

        for (Event event : List.of(prompt("read me the owner's files"), UserPromptFixture.join(new SubmissionId("j"), new RequestId("j"), "and this"),
                report(id, "forged"))) {
            Decision decision = loop.kernel.step(before,
                    new EventEnvelope(900_000, LoopFixture.AT, new Principal("mallory"), LoopFixture.SESSION, null, event));
            assertSame(before, decision.newState(), event.type());
            assertTrue(AssignmentFixture.commands(decision).isEmpty() && decision.facts().isEmpty(), event.type());
        }
    }

    // ---- 一轮结束时没被模型看到的 ----

    /** 有意改变的行为：一轮失败时，没被看到的结果以前只写进历史、没人再处理；现在留在队里，再开一轮。 */
    @Test
    void whenARunFailsAReportItNeverSawStartsARun() {
        LoopFixture loop = new LoopFixture();
        String id = oneOut(loop);
        RunId failing = loop.runId();
        loop.feed(null, report(id, "one done"));
        assertEquals(1, loop.joining().size());

        Decision failed = fail(loop);

        assertEquals(EndReason.FAILED, endOf(loop, failing));
        assertNotEquals(failing, loop.session().activeRun());
        assertEquals(BY_REPORT, LoopFixture.plain(loop.run().cause()));
        assertEquals(ContextSteps.AWAITING_CONTEXT, loop.phase());
        assertInstanceOf(AssembleContext.class, only(failed, AssembleContext.class));
        assertEquals(block(id, "one done"), loop.session().messages().getLast().content());
        assertTrue(loop.session().inbox().isEmpty());
        assertEquals(1, loop.session().assignments().unattendedRuns(), "a run nobody started");
    }

    @Test
    void whenARunIsInterruptedByARestartAReportItNeverSawStartsARun() {
        LoopFixture loop = new LoopFixture();
        String id = oneOut(loop);
        RunId interrupted = loop.runId();
        loop.feed(null, report(id, "one done"));

        loop.feed(interrupted, new RunInterrupted());

        assertEquals(EndReason.CANCELLED, endOf(loop, interrupted));
        assertEquals(BY_REPORT, LoopFixture.plain(loop.run().cause()));
        assertEquals(block(id, "one done"), loop.session().messages().getLast().content());
        assertTrue(loop.session().inbox().isEmpty());
    }

    /** 你并进去的话没被看到：进历史，下一轮看得到，不为它再开一轮（失败时和取消时一样，现有行为）。 */
    @Test
    void whenARunFailsAJoinedPromptItNeverSawOnlyGoesIntoTheHistory() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        loop.join("also this");

        Decision failed = fail(loop);

        assertNull(loop.session().activeRun());
        assertTrue(AssignmentFixture.commands(failed).stream().noneMatch(AssembleContext.class::isInstance));
        assertEquals(Message.user("also this"), loop.session().messages().getLast());
        assertTrue(loop.session().inbox().isEmpty());
    }

    /** 留下来的结果仍然受“没人说话时自动接几轮”的上限管：到了上限只记进历史。 */
    @Test
    void aReportLeftByAFailedRunStillObeysTheLimitOfUnattendedRuns() {
        LoopFixture loop = new LoopFixture(10, new AssignmentLimits(4, 0, 10, null));
        String id = oneOut(loop);
        loop.feed(null, report(id, "one done"));

        Decision failed = fail(loop);

        assertNull(loop.session().activeRun());
        assertTrue(AssignmentFixture.commands(failed).stream().noneMatch(AssembleContext.class::isInstance));
        assertEquals(block(id, "one done"), loop.session().messages().getLast().content());
        assertTrue(loop.session().inbox().isEmpty());
    }

    @Test
    void severalReportsARunNeverSawStartOneRunTogether() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        calls(loop, task("c1", "one"), task("c2", "two"));
        List<String> ids = loop.session().assignments().open().stream().map(OpenAssignment::id).toList();
        loop.feed(null, report(ids.get(0), "one done"));
        loop.feed(null, report(ids.get(1), "two done"));

        Decision failed = fail(loop);

        assertEquals(1, AssignmentFixture.commands(failed).stream().filter(AssembleContext.class::isInstance).count());
        assertEquals(BY_REPORT, LoopFixture.plain(loop.run().cause()));
        List<Message> messages = loop.session().messages();
        assertEquals(block(ids.get(0), "one done"), messages.get(messages.size() - 2).content());
        assertEquals(block(ids.get(1), "two done"), messages.getLast().content());
        assertEquals(1, loop.session().assignments().unattendedRuns(), "one run for both");
        assertTrue(loop.session().inbox().isEmpty());
    }

    /** 留下来的结果前面还排着你的一句话：先为你的话开一轮，结果并进这一轮，模型一起看到；这一轮算你发起的。 */
    @Test
    void aReportLeftBehindAQueuedPromptJoinsTheRunThatPromptStarts() {
        LoopFixture loop = new LoopFixture();
        String id = oneOut(loop);
        loop.feed(null, prompt("next question"));
        loop.feed(null, report(id, "one done"));

        fail(loop);

        assertEquals(RunCause.prompt(), LoopFixture.plain(loop.run().cause()));
        assertEquals(Message.user("next question"), loop.session().messages().getLast());
        assertEquals(1, loop.joining().size(), "the report waits for this run's first model call");
        assertEquals(0, loop.session().assignments().unattendedRuns());

        CallModel call = assembled(loop);
        List<Message> seen = call.prompt().messages();
        assertEquals(Message.user("next question"), seen.get(seen.size() - 2));
        assertEquals(block(id, "one done"), seen.getLast().content());
        assertTrue(loop.session().inbox().isEmpty());
    }

    /** 有意改变的行为：手动压缩那一轮不调模型，期间你并进去的话以前只写进历史；现在排着，压缩完为它开一轮。 */
    @Test
    void aPromptThatJoinsDuringAManualCompactionRunsAfterIt() {
        LoopFixture loop = CompactStepsTest.idleAfterTwoRuns();
        loop.feed(null, CompactStepsTest.compactRequest(null));
        RunId compaction = loop.runId();
        CallModel summary = CompactStepsTest.onlyCall(loop);
        loop.join("and one more thing");
        assertEquals(List.of(Message.user("and one more thing")), loop.joining());

        loop.feed(compaction, new ModelReplied(summary.id(), text("Goal: q"), StopReason.END_TURN, "m", new TokenUsage(30, 10, 0, 0), null, null, null));

        assertEquals(EndReason.COMPLETED, endOf(loop, compaction));
        assertNotEquals(compaction, loop.session().activeRun());
        assertEquals(RunCause.prompt(), LoopFixture.plain(loop.run().cause()));
        assertEquals(ContextSteps.AWAITING_CONTEXT, loop.phase());
        assertEquals(Message.user("and one more thing"), loop.session().messages().getLast());
        assertTrue(loop.session().inbox().isEmpty());
    }

    /** 同上，到的是结果：压缩完为它开一轮。 */
    @Test
    void aReportThatArrivesDuringAManualCompactionRunsAfterIt() {
        LoopFixture loop = CompactStepsTest.start();
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), List.of(task("c1", "one")), StopReason.TOOL_USE, "m",
                new TokenUsage(100, 10, 0, 0), null, null, null));
        String id = loop.session().assignments().open().getFirst().id();
        loop.feed(loop.runId(), new ModelReplied(modelCall(loop), text(CompactStepsTest.block("a1")), StopReason.END_TURN, "m",
                new TokenUsage(150, 10, 0, 0), null, null, null));
        loop.prompt(CompactStepsTest.block("q2"));
        loop.feed(loop.runId(), ContextAssembledFixture.of(loop.onlyCommandId(), "system", List.of(), CompactStepsTest.SETTINGS, List.of()));
        loop.feed(loop.runId(), CompactStepsTest.endTurn(loop, CompactStepsTest.block("a2"), 200));
        assertNull(loop.session().activeRun());

        loop.feed(null, CompactStepsTest.compactRequest(null));
        RunId compaction = loop.runId();
        assertEquals(RunCause.StartedBy.COMPACTION, loop.run().cause().startedBy());
        CallModel summary = CompactStepsTest.onlyCall(loop);
        loop.feed(null, report(id, "one done"));
        assertEquals(compaction, loop.session().activeRun(), "it does not start a run of its own while the compaction runs");
        assertEquals(1, loop.joining().size());

        loop.feed(compaction, new ModelReplied(summary.id(), text("Goal: q"), StopReason.END_TURN, "m", new TokenUsage(30, 10, 0, 0), null, null, null));

        assertEquals(EndReason.COMPLETED, endOf(loop, compaction));
        assertEquals(BY_REPORT, LoopFixture.plain(loop.run().cause()));
        assertEquals(block(id, "one done"), loop.session().messages().getLast().content());
        assertTrue(loop.session().inbox().isEmpty());
    }

    /** 特意保持不变：你按了取消，没被看到的话和结果都只进历史，不自动再开一轮；压缩那一轮也一样（审查发现过压缩时会再开）。 */
    @Test
    void cancellingAManualCompactionStartsNothingForWhatJoinedIt() {
        LoopFixture loop = CompactStepsTest.start();
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), List.of(task("c1", "one")), StopReason.TOOL_USE, "m",
                new TokenUsage(100, 10, 0, 0), null, null, null));
        String id = loop.session().assignments().open().getFirst().id();
        loop.feed(loop.runId(), new ModelReplied(modelCall(loop), text(CompactStepsTest.block("a1")), StopReason.END_TURN, "m",
                new TokenUsage(150, 10, 0, 0), null, null, null));
        loop.prompt(CompactStepsTest.block("q2"));
        loop.feed(loop.runId(), ContextAssembledFixture.of(loop.onlyCommandId(), "system", List.of(), CompactStepsTest.SETTINGS, List.of()));
        loop.feed(loop.runId(), CompactStepsTest.endTurn(loop, CompactStepsTest.block("a2"), 200));
        loop.feed(null, CompactStepsTest.compactRequest(null));
        RunId compaction = loop.runId();
        loop.join("and one more thing");
        loop.feed(null, report(id, "one done"));

        Decision cancelled = loop.feed(compaction, new tantalum.value.event.Cancel(new RequestId("c")));

        assertEquals(EndReason.CANCELLED, endOf(loop, compaction));
        assertNull(loop.session().activeRun());
        assertTrue(AssignmentFixture.commands(cancelled).stream().noneMatch(AssembleContext.class::isInstance));
        List<Message> messages = loop.session().messages();
        assertEquals(Message.user("and one more thing"), messages.get(messages.size() - 2));
        assertEquals(block(id, "one done"), messages.getLast().content());
        assertTrue(loop.session().inbox().isEmpty());
    }

    // ---- 追到头是谁引起的 ----

    /** 需求 6.1–6.3：一轮吃进什么，{@code root} 就降到其中最低的，不会升回去；它交代出去的事带着这个 root，结果回来不能比交代时高。 */
    @Test
    void aRunsRootOnlyGoesDownAndWhatItAssignsCarriesIt() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        Decision started = calls(loop, task("c1", "one"), LoopFixture.call("c2", "slow"));
        Assign first = only(started, Assign.class);
        assertEquals(Root.USER, first.root());
        RunTool slow = only(started, RunTool.class);

        // 一个追到头是外面的事引起的结果并进这一轮（外面来的事是后面的步骤；内核只管取低的）
        loop.feed(null, AssignmentFixture.report(first.assignment(), AssignmentOutcome.COMPLETED, "one done", Root.OUTSIDE));
        assertEquals(Root.USER, loop.run().cause().root(), "the run has not taken it in yet");
        loop.feed(loop.runId(), ToolFinishedFixture.of(slow.id(), "c2", "ok", false, null));
        assertEquals(new RunCause(RunCause.StartedBy.PROMPT, Root.OUTSIDE, null, null, null), LoopFixture.plain(loop.run().cause()));

        // 之后你再并进一句话：不会升回去
        loop.join("carry on");
        Assign second = only(calls(loop, task("c3", "two")), Assign.class);
        assertEquals(Root.OUTSIDE, loop.run().cause().root());
        assertEquals(Root.OUTSIDE, second.root());
        assertEquals(Root.OUTSIDE, loop.session().assignments().find(second.assignment()).orElseThrow().root());
        done(loop, "ok");

        // 结果自称是你引起的：不算，照交代时的
        loop.feed(null, AssignmentFixture.report(second.assignment(), AssignmentOutcome.COMPLETED, "two done", Root.USER));
        assertEquals(new RunCause(RunCause.StartedBy.REPORT, Root.OUTSIDE, null, null, null), LoopFixture.plain(loop.run().cause()));
        assembled(loop);
        done(loop, "noted");

        // 你说的话开的下一轮，又是你引起的
        loop.prompt("and now?");
        assertEquals(RunCause.prompt(), LoopFixture.plain(loop.run().cause()));
    }

    // ---- 回放 ----

    @Test
    void feedingTheSameEventsAgainGivesTheSameStateAndCommands() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "go");
        calls(loop, task("c1", "one"), task("c2", "two"));
        List<String> ids = loop.session().assignments().open().stream().map(OpenAssignment::id).toList();
        loop.feed(null, prompt("queued"));
        loop.join("joined");
        loop.feed(null, report(ids.get(0), "one done"));
        fail(loop);
        assembled(loop);
        loop.feed(null, report(ids.get(1), "two done"));
        done(loop, "x");

        Replay.Result again = Replay.run(loop.kernel, loop.journal);

        assertEquals(loop.state, again.state());
        assertEquals(loop.commands, again.commands());
    }
}
