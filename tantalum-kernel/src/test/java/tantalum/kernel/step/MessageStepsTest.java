package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.kernel.Kernel;
import tantalum.kernel.Limits;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.table.TransitionTable;
import tantalum.value.command.AbortCommands;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.RunTool;
import tantalum.value.command.Send;
import tantalum.value.command.StartTimer;
import tantalum.value.decision.Decision;
import tantalum.value.delegate.DelegateCall;
import tantalum.value.event.Cancel;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.RunInterrupted;
import tantalum.value.event.SessionMessage;
import tantalum.value.event.TimerFired;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.AwaitingReply;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/**
 * 会话之间说话，在内核里的每一步。只有一种事件（会话来话）、一种命令（送话）、一个状态（等会话回答）。几个会话在同一棵状态树里，
 * 测试自己当“邮递员”：把内核发的送话命令变成对方会话的事件。没有线程、没有时钟；同一串事件再喂一遍结果相同。
 */
class MessageStepsTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId A = new SessionId("a");
    private static final Instant AT = Instant.parse("2026-11-16T01:00:00Z");

    /** 喂事件的小台子：记下每一步发的命令，和喂过的事件（回放用）。 */
    private static final class World {
        final Kernel kernel;
        KernelState state = KernelState.empty();
        final List<EventEnvelope> journal = new ArrayList<>();
        final List<Command> commands = new ArrayList<>();
        Decision last;
        long seq;

        World() {
            TransitionTable table = new TransitionTable();
            BuiltinSteps.register(table);
            kernel = new Kernel(table, new HookChain(List.of()), (tool, args) -> !"write_file".equals(tool), Limits.DEFAULT);
        }

        Decision feed(SessionId session, RunId run, Event event) {
            EventEnvelope envelope = new EventEnvelope(seq++, AT, USER, session, run, event);
            journal.add(envelope);
            last = kernel.step(state, envelope);
            state = last.newState();
            commands.addAll(last.commands());
            return last;
        }

        SessionState session(SessionId id) {
            return state.sessions().get(id);
        }

        RunState run(SessionId id) {
            SessionState session = session(id);
            return state.runs().get(session.activeRun() != null ? session.activeRun() : session.lastRun());
        }

        <T extends Command> T only(Class<T> type) {
            List<T> found = last.commands().stream().filter(type::isInstance).map(type::cast).toList();
            assertEquals(1, found.size(), type.getSimpleName() + " in " + last.commands());
            return found.getFirst();
        }

        /** 用户提问到模型第一次被调用：返回那次调用的命令。 */
        CallModel ask(SessionId session, String text) {
            feed(session, null, new UserPrompt(new SubmissionId("sub" + seq), new RequestId("req" + seq), new OwnerRef.OfSession(session), text));
            return assembled(session, only(AssembleContext.class));
        }

        CallModel assembled(SessionId session, AssembleContext start) {
            feed(session, run(session).ref().run(), new ContextAssembled(start.id(), "sys", List.of(), null, List.of()));
            return only(CallModel.class);
        }

        Decision reply(SessionId session, CallModel call, ContentBlock... content) {
            boolean tools = List.of(content).stream().anyMatch(ToolCallBlock.class::isInstance);
            return feed(session, run(session).ref().run(), new ModelReplied(call.id(), List.of(content),
                    tools ? StopReason.TOOL_USE : StopReason.END_TURN, "m", null, null, null, null));
        }

        /** 邮递员：把一句话送进收件的会话。 */
        Decision deliver(Send command) {
            return feed(command.to(), null, command.message());
        }
    }

    private static ToolCallBlock delegate(String id, String task, boolean wait, String session) {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("task", new Json.Str(task));
        fields.put("wait", new Json.Bool(wait));
        if (session != null) {
            fields.put("session", new Json.Str(session));
        }
        return new ToolCallBlock(id, "delegate", "{}", new Json.Obj(fields));
    }

    private static List<ToolResultBlock> results(World world, SessionId session) {
        List<tantalum.value.model.Message> messages = world.session(session).messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            List<ToolResultBlock> found = messages.get(i).content().stream().filter(ToolResultBlock.class::isInstance)
                    .map(ToolResultBlock.class::cast).toList();
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    private static String lastText(World world, SessionId session) {
        return ((TextBlock) world.session(session).messages().getLast().content().getFirst()).text();
    }

    /** A 问另一个会话并等着：到“等会话回答”为止，返回那条送话命令。 */
    private static Send asked(World world) {
        CallModel call = world.ask(A, "count the files");
        world.reply(A, call, delegate("c1", "count the files in src", true, null));
        return world.only(Send.class);
    }

    /** 收到话的会话把这件事做完：返回它送回去的那句话。 */
    private static Send finishes(World world, Send sent, String answer) {
        world.deliver(sent);
        CallModel call = world.assembled(sent.to(), world.only(AssembleContext.class));
        world.reply(sent.to(), call, new TextBlock(answer));
        return world.only(Send.class);
    }

    @Test
    void askingAndWaitingRestsInAStateNotInAThread() {
        World world = new World();
        Send sent = asked(world);
        assertEquals("s-" + sent.id().value(), sent.to().value(), "a new conversation's id comes from the message id");
        assertEquals(sent.id(), sent.message().id());
        assertEquals(A, sent.message().from().session());
        assertFalse(sent.message().isReply());
        assertEquals(1, sent.message().hops());
        assertTrue(sent.message().text().contains("count the files in src"));
        AwaitingReply waiting = assertInstanceOf(AwaitingReply.class, world.run(A).phase());
        assertEquals(AT.plus(MessageSteps.LIMIT), waiting.deadline());
        assertEquals(waiting.timer(), world.only(StartTimer.class).id());
        assertTrue(world.last.commands().stream().noneMatch(RunTool.class::isInstance), "delegate is not run as a tool");
    }

    @Test
    void theOtherConversationRunsATurnForItAndItsAnswerBecomesTheToolResult() {
        World world = new World();
        Send sent = asked(world);
        world.deliver(sent);
        assertEquals(new OwnerRef.OfMessage(sent.id(), A, 1), world.run(sent.to()).owner());
        CallModel call = world.assembled(sent.to(), world.only(AssembleContext.class));
        world.reply(sent.to(), call, new TextBlock("42 files"));
        Send back = world.only(Send.class);
        assertEquals(A, back.to());
        assertEquals(sent.id(), back.message().answers());
        assertEquals("42 files", back.message().text());
        assertFalse(back.message().failed());

        world.deliver(back);
        assertEquals("Answer from conversation " + sent.to().value() + ":\n42 files", results(world, A).getFirst().text());
        world.only(CallModel.class);
        assertTrue(world.last.commands().stream().anyMatch(AbortCommands.class::isInstance), "the deadline timer is not needed any more");
    }

    @Test
    void withoutWaitingTheCallIsDoneAtOnceAndTheAnswerComesBackAsAMessage() {
        World world = new World();
        CallModel call = world.ask(A, "long job");
        world.reply(A, call, delegate("c1", "a long job", false, null));
        Send sent = world.only(Send.class);
        CallModel next = world.only(CallModel.class);
        assertTrue(results(world, A).getFirst().text().startsWith("Opened conversation " + sent.to().value()));
        world.reply(A, next, new TextBlock("I handed it off."));
        assertInstanceOf(Ended.class, world.run(A).phase());

        Send back = finishes(world, sent, "the long job is done");
        world.deliver(back);
        assertNotNull(world.session(A).activeRun(), "the answer woke the idle conversation: a new run started");
        assertEquals(new OwnerRef.OfSession(A), world.run(A).owner(), "and that run answers nobody");
        assertEquals(DelegateCall.REPORT + "Conversation " + sent.to().value() + " finished:\nthe long job is done", lastText(world, A));
        world.only(AssembleContext.class);
    }

    @Test
    void anAnswerThatArrivesWhileBusyWithSomethingElseBecomesASteer() {
        World world = new World();
        CallModel call = world.ask(A, "long job");
        world.reply(A, call, delegate("c1", "a long job", false, null));
        Send sent = world.only(Send.class);
        Send back = finishes(world, sent, "done early");
        world.deliver(back);
        assertEquals(1, world.run(A).pendingSteers().size(), "A's run is still going: it sees the answer at its next step");
        assertTrue(world.last.commands().stream().allMatch(tantalum.value.command.Confirm.class::isInstance), "only the confirmation goes out");
    }

    @Test
    void twoQuestionsInOneReplyWaitForBothAndKeepTheModelsOrder() {
        World world = new World();
        CallModel call = world.ask(A, "two things");
        world.reply(A, call, delegate("c1", "first", true, null), delegate("c2", "second", true, null));
        List<Send> sent = world.last.commands().stream().filter(Send.class::isInstance).map(Send.class::cast).toList();
        assertEquals(2, sent.size());
        world.deliver(finishes(world, sent.get(1), "B"));
        assertInstanceOf(AwaitingReply.class, world.run(A).phase(), "still waiting for the first");
        assertTrue(world.last.commands().stream().allMatch(tantalum.value.command.Confirm.class::isInstance), "only the confirmation goes out");
        world.deliver(finishes(world, sent.get(0), "A"));
        world.only(CallModel.class);
        List<ToolResultBlock> answers = results(world, A);
        assertTrue(answers.get(0).text().endsWith("A") && answers.get(1).text().endsWith("B"), answers.toString());
    }

    @Test
    void aQuestionNextToAnOrdinaryToolWaitsForBoth() {
        World world = new World();
        CallModel call = world.ask(A, "mixed");
        world.reply(A, call, new ToolCallBlock("t1", "read_file", "{}", new Json.Obj(Map.of())), delegate("c2", "side job", true, null));
        RunTool tool = world.only(RunTool.class);
        Send sent = world.only(Send.class);
        assertEquals(ToolSteps.AWAITING_TOOLS, world.run(A).phase().name());
        world.deliver(finishes(world, sent, "done"));
        assertEquals(ToolSteps.AWAITING_TOOLS, world.run(A).phase().name(), "the tool is still running");
        world.feed(A, world.run(A).ref().run(), new ToolFinished(tool.id(), "t1", "file text", false, null, null));
        world.only(CallModel.class);
    }

    @Test
    void theDeadlineEndsTheWaitAndALateAnswerStillArrivesAsAMessage() {
        World world = new World();
        Send sent = asked(world);
        AwaitingReply waiting = (AwaitingReply) world.run(A).phase();
        world.feed(A, world.run(A).ref().run(), new TimerFired(waiting.timer()));
        CallModel next = world.only(CallModel.class);
        ToolResultBlock timedOut = results(world, A).getFirst();
        assertTrue(timedOut.error() && timedOut.text().startsWith("No answer within 10 minutes"), timedOut.text());
        world.reply(A, next, new TextBlock("It is taking long."));
        world.deliver(finishes(world, sent, "42 files"));
        assertTrue(lastText(world, A).endsWith("42 files"), "the late answer is not lost");
    }

    @Test
    void cancellingWhileWaitingEndsTheRunAndStopsTheTimer() {
        World world = new World();
        asked(world);
        world.feed(A, null, new Cancel(new RequestId("x")));
        assertEquals(EndReason.CANCELLED, ((Ended) world.run(A).phase()).reason());
        assertTrue(world.last.commands().stream().anyMatch(AbortCommands.class::isInstance));
        assertTrue(results(world, A).getFirst().text().contains("before the other conversation answered"));
    }

    @Test
    void aRestartDoesNotInterruptTheWait() {
        World world = new World();
        asked(world);
        world.feed(A, world.run(A).ref().run(), new RunInterrupted());
        assertInstanceOf(AwaitingReply.class, world.run(A).phase());
    }

    @Test
    void aConversationThatFailsOrIsInterruptedSaysSo() {
        World world = new World();
        Send sent = asked(world);
        world.deliver(sent);
        world.feed(sent.to(), world.run(sent.to()).ref().run(), new RunInterrupted());
        Send back = world.only(Send.class);
        assertTrue(back.message().failed());
        world.deliver(back);
        ToolResultBlock result = results(world, A).getFirst();
        assertTrue(result.error() && result.text().contains("interrupted by a server restart")
                && result.text().contains("session=\"" + sent.to().value() + "\""), result.text());
    }

    @Test
    void workCanGoToAnExistingConversationAndABusyOneQueuesIt() {
        World world = new World();
        CallModel call = world.ask(A, "start");
        world.reply(A, call, delegate("c1", "first job", false, null));
        Send first = world.only(Send.class);
        CallModel next = world.only(CallModel.class);
        world.deliver(first);
        AssembleContext start = world.only(AssembleContext.class);
        world.reply(A, next, delegate("c2", "second job", false, first.to().value()));
        Send second = world.only(Send.class);
        assertEquals(first.to(), second.to());
        assertTrue(results(world, A).getFirst().text().startsWith("Handed to conversation " + first.to().value()));
        world.deliver(second);
        assertEquals(List.of(second.message()), world.session(first.to()).inbox().waiting());

        CallModel working = world.assembled(first.to(), start);
        world.reply(first.to(), working, new TextBlock("first done"));
        assertEquals(first.id(), world.only(Send.class).message().answers());
        assertEquals(new OwnerRef.OfMessage(second.id(), A, 1), world.run(first.to()).owner(), "the queued message started");
        assertTrue(world.session(first.to()).inbox().isEmpty());
    }

    @Test
    void aNewConversationCanBeOpenedInAnotherWorkspace() {
        World world = new World();
        CallModel call = world.ask(A, "two jobs");
        ToolCallBlock elsewhere = new ToolCallBlock("c1", "delegate", "{}", new Json.Obj(Map.of("task", new Json.Str("fix the docs"),
                "workspace", new Json.Str("/home/u/docs"))));
        world.reply(A, call, elsewhere, delegate("c2", "same place", false, null));
        List<Send> sent = world.last.commands().stream().filter(Send.class::isInstance).map(Send.class::cast).toList();
        assertEquals("/home/u/docs", sent.get(0).message().dir(), "the workspace's folder goes with the first message");
        assertNull(sent.get(1).message().dir(), "without a workspace it is the same folder as this conversation (none here)");
        assertTrue(results(world, A).getFirst().text().contains(" in /home/u/docs"));
        world.deliver(sent.get(0));
        assertEquals("/home/u/docs", world.session(sent.get(0).to()).dir());
    }

    // ---- 话不丢、不重：送出去记着等确认，重启后重送，收的一方按编号只处理一次 ----

    @Test
    void whatIsSentStaysUnconfirmedUntilTheOtherSideSaysItArrived() {
        World world = new World();
        CallModel call = world.ask(A, "do it");
        world.reply(A, call, delegate("c1", "count", false, null));
        Send sent = world.only(Send.class);
        assertEquals(List.of(sent.message().id()), world.session(A).inbox().unconfirmed().stream().map(each -> each.message().id()).toList(),
                "recorded in the same step that sends it");
        assertEquals(sent.to(), world.session(A).inbox().unconfirmed().getFirst().to());

        world.deliver(sent);
        tantalum.value.command.Confirm confirm = world.only(tantalum.value.command.Confirm.class);
        assertEquals(A, confirm.to());
        assertEquals(sent.message().id(), confirm.message());
        assertTrue(world.session(sent.to()).inbox().hasSeen(sent.message().id()));

        world.feed(A, null, new tantalum.value.event.MessageDelivered(confirm.message()));
        assertTrue(world.session(A).inbox().unconfirmed().isEmpty(), "confirmed: nothing left to send again");
        KernelState before = world.state;
        world.feed(A, null, new tantalum.value.event.MessageDelivered(confirm.message()));
        assertEquals(before, world.state, "a second confirmation changes nothing");
    }

    @Test
    void afterARestartUnconfirmedMessagesAreSentAgainAndTakenOnlyOnce() {
        World world = new World();
        CallModel call = world.ask(A, "do it");
        world.reply(A, call, delegate("c1", "count", false, null));
        Send sent = world.only(Send.class);
        world.deliver(sent);   // 送到了，但确认没回来进程就死了
        SessionId other = sent.to();
        int runsBefore = world.state.runs().size();

        world.feed(A, null, new tantalum.value.event.Redeliver());
        Send again = world.only(Send.class);
        assertEquals(sent.message(), again.message(), "the same message with the same id");
        assertEquals(other, again.to());
        assertEquals(1, world.session(A).inbox().unconfirmed().size(), "still one entry, not two");

        KernelState before = world.state;
        world.deliver(again);
        assertEquals(runsBefore, world.state.runs().size(), "the duplicate does not start a second turn");
        assertEquals(before.sessions().get(other).messages(), world.session(other).messages(), "nor add anything to the conversation");
        assertEquals(sent.message().id(), world.only(tantalum.value.command.Confirm.class).message(), "but it is confirmed again");

        // 没送到就死了的那种：对方从没见过，重送的就是第一次
        World lost = new World();
        CallModel first = lost.ask(A, "do it");
        lost.reply(A, first, delegate("c1", "count", false, null));
        lost.feed(A, null, new tantalum.value.event.Redeliver());
        Send resent = lost.only(Send.class);
        lost.deliver(resent);
        assertEquals("count", tantalum.value.delegate.DelegateCall.taskOf(((tantalum.value.model.TextBlock) lost.session(resent.to())
                .messages().getFirst().content().getFirst()).text()), "it arrives and starts the work");
        assertTrue(lost.run(resent.to()).owner() instanceof OwnerRef.OfMessage);
    }

    @Test
    void theAnswerBackIsAlsoHeldUntilConfirmedAndADuplicateAnswerIsDropped() {
        World world = new World();
        CallModel call = world.ask(A, "ask");
        world.reply(A, call, delegate("c1", "count", true, null));
        Send request = world.only(Send.class);
        world.deliver(request);
        SessionId other = request.to();
        CallModel work = world.assembled(other, world.only(AssembleContext.class));
        world.reply(other, work, new tantalum.value.model.TextBlock("42"));
        Send answer = world.only(Send.class);
        assertEquals(List.of(answer.message().id()), world.session(other).inbox().unconfirmed().stream().map(each -> each.message().id()).toList());

        world.deliver(answer);
        assertTrue(results(world, A).getFirst().text().contains("42"));
        int messages = world.session(A).messages().size();
        world.deliver(answer);
        assertEquals(messages, world.session(A).messages().size(), "the same answer a second time is not taken again");
    }

    @Test
    void redeliverWithNothingPendingChangesNothing() {
        World world = new World();
        world.ask(A, "hello");
        KernelState before = world.state;
        world.feed(A, null, new tantalum.value.event.Redeliver());
        assertEquals(before, world.state);
        assertTrue(world.last.commands().isEmpty());
    }

    @Test
    void whatTheUserTypesInThatConversationIsNotSentBack() {
        World world = new World();
        CallModel call = world.ask(A, "go");
        world.reply(A, call, delegate("c1", "count", false, null));
        Send sent = world.only(Send.class);
        world.deliver(finishes(world, sent, "42"));
        CallModel own = world.ask(sent.to(), "and how many are tests?");
        assertEquals(new OwnerRef.OfSession(sent.to()), world.run(sent.to()).owner());
        world.reply(sent.to(), own, new TextBlock("7"));
        assertTrue(world.last.commands().stream().noneMatch(Send.class::isInstance));
    }

    @Test
    void aTurnThatAnswersAMessageCannotHandItOn() {
        World world = new World();
        Send first = asked(world);
        world.deliver(first);
        CallModel b = world.assembled(first.to(), world.only(AssembleContext.class));
        world.reply(first.to(), b, delegate("c1", "pass it on", true, null));
        assertTrue(world.last.commands().stream().noneMatch(Send.class::isInstance), "only one level: it does the work itself");
        ToolResultBlock refused = results(world, first.to()).getFirst();
        assertTrue(refused.error() && refused.text().contains("do the work here"), refused.text());
        // 但用户自己在那个会话里开的一轮可以交出去
        world.reply(first.to(), world.only(CallModel.class), new TextBlock("did it myself"));
        CallModel own = world.ask(first.to(), "now ask someone else");
        world.reply(first.to(), own, delegate("c2", "another job", false, null));
        assertEquals(1, world.only(Send.class).message().hops());

        // 听话的一方也查：转手太多的话不接，回一句“不接”
        SessionId other = new SessionId("z");
        world.feed(other, null, new SessionMessage(new tantalum.value.id.CommandId("m-9"), new RunRef(USER, A, new RunId("r")), "x", null,
                false, MessageSteps.MAX_HOPS + 1, null, null, null));
        Send no = world.only(Send.class);
        assertTrue(no.message().failed() && no.message().isReply());
        assertNull(world.session(other));
    }

    @Test
    void badArgumentsAndTooManyAtOnceAnswerTheModelWithoutSending() {
        World world = new World();
        CallModel call = world.ask(A, "bad");
        world.reply(A, call, new ToolCallBlock("c1", "delegate", "{}", new Json.Obj(Map.of())), delegate("c2", "x", false, A.value()));
        assertTrue(world.last.commands().stream().noneMatch(Send.class::isInstance));
        List<ToolResultBlock> answers = results(world, A);
        assertTrue(answers.get(0).error() && answers.get(0).text().contains("\"task\""), answers.get(0).text());
        assertTrue(answers.get(1).error() && answers.get(1).text().contains("this conversation itself"));

        ToolCallBlock[] many = new ToolCallBlock[MessageSteps.MAX_ASKED + 1];
        for (int i = 0; i < many.length; i++) {
            many[i] = delegate("m" + i, "job " + i, true, null);
        }
        world.reply(A, world.only(CallModel.class), many);
        assertEquals(MessageSteps.MAX_ASKED, world.last.commands().stream().filter(Send.class::isInstance).count());
    }

    @Test
    void aMessageFromAnotherUserIsIgnored() {
        World world = new World();
        RunRef stranger = new RunRef(new Principal("someone-else"), A, new RunId("r"));
        world.feed(new SessionId("x"), null, new SessionMessage(new tantalum.value.id.CommandId("m-1"), stranger, "do this", null, false, 1,
                null, null, null));
        assertNull(world.session(new SessionId("x")));
        assertTrue(world.last.commands().isEmpty());
    }

    @Test
    void feedingTheSameEventsAgainGivesTheSameStateAndCommands() {
        World world = new World();
        Send sent = asked(world);
        world.deliver(finishes(world, sent, "42 files"));
        World replay = new World();
        for (EventEnvelope envelope : world.journal) {
            replay.feed(envelope.session(), envelope.run(), envelope.event());
        }
        assertEquals(world.state, replay.state);
        assertEquals(world.commands, replay.commands);
    }

    private static ToolCallBlock stop(String id, String session) {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("stop", new Json.Bool(true));
        if (session != null) {
            fields.put("session", new Json.Str(session));
        }
        return new ToolCallBlock(id, "delegate", "{}", new Json.Obj(fields));
    }

    /**
     * 撤回：A 交给 B 两件（一件在做、一件排着队），然后收回。B 排队的那件去掉、在做的那一轮按被取消结束，两件各回一句“没做成”，
     * 所以每件交出去的活仍然恰好有一句回话。
     */
    @Test
    void takingWorkBackEndsWhatIsNotFinishedAndEachPieceStillAnswers() {
        World world = new World();
        CallModel call = world.ask(A, "start");
        world.reply(A, call, delegate("c1", "first job", false, null));
        Send first = world.only(Send.class);
        CallModel next = world.only(CallModel.class);
        world.deliver(first);
        world.reply(A, next, delegate("c2", "second job", false, first.to().value()));
        Send second = world.only(Send.class);
        next = world.only(CallModel.class);
        world.deliver(second);
        SessionId b = first.to();
        assertEquals(1, world.session(b).inbox().waiting().size());

        world.reply(A, next, stop("c3", b.value()));
        Send back = world.only(Send.class);
        assertTrue(back.message().isWithdrawal() && !back.message().isReply() && !back.message().isRequest());
        assertEquals(b, back.to());
        ToolResultBlock told = results(world, A).getFirst();
        assertTrue(!told.error() && told.text().startsWith("Told conversation " + b.value() + " to stop"), told.text());
        assertTrue(world.session(A).inbox().unconfirmed().stream().anyMatch(each -> each.message().id().equals(back.id())),
                "held until the other side confirms, like any message");

        world.deliver(back);
        assertEquals(EndReason.CANCELLED, ((Ended) world.run(b).phase()).reason());
        assertNull(world.session(b).activeRun(), "nothing else started");
        assertTrue(world.session(b).inbox().isEmpty());
        assertTrue(world.last.commands().stream().anyMatch(AbortCommands.class::isInstance), "what the turn was waiting for is aborted");
        assertEquals(1, world.last.commands().stream().filter(tantalum.value.command.Confirm.class::isInstance).count());
        List<Send> answers = world.last.commands().stream().filter(Send.class::isInstance).map(Send.class::cast).toList();
        assertEquals(2, answers.size(), "one answer per piece");
        assertTrue(answers.stream().allMatch(each -> each.to().equals(A) && each.message().failed()));
        assertEquals(java.util.Set.of(first.id(), second.id()),
                answers.stream().map(each -> each.message().answers()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(answers.stream().anyMatch(each -> each.message().text().contains("before it started")));
        assertTrue(answers.stream().anyMatch(each -> each.message().text().startsWith("it was cancelled: taken back")), answers.toString());

        // 再送一遍同一句撤回（重启后的重送）：只回确认
        world.deliver(back);
        assertTrue(world.last.commands().stream().noneMatch(Send.class::isInstance));
    }

    /** 撤回只管交活的那个会话交来的、还没做完的：已经做完的、用户自己在那里问的、别的会话交来的都不动。 */
    @Test
    void takingBackLeavesFinishedWorkTheUsersOwnTurnsAndOtherConversationsWorkAlone() {
        World world = new World();
        CallModel call = world.ask(A, "go");
        world.reply(A, call, delegate("c1", "count", false, null));
        Send sent = world.only(Send.class);
        CallModel next = world.only(CallModel.class);
        SessionId b = sent.to();
        world.deliver(finishes(world, sent, "42"));
        // B 已经做完；用户自己在 B 里又问了一句，正在跑
        world.ask(b, "and how many are tests?");
        RunState own = world.run(b);

        world.reply(A, next, stop("c2", b.value()));
        Send back = world.last.commands().stream().filter(Send.class::isInstance).map(Send.class::cast)
                .filter(each -> each.message().isWithdrawal()).findFirst().orElseThrow();
        world.deliver(back);
        assertEquals(own, world.run(b), "the user's own turn goes on");
        assertTrue(world.last.commands().stream().noneMatch(Send.class::isInstance), "nothing to answer: the work was finished");
        assertEquals(1, world.last.commands().stream().filter(tantalum.value.command.Confirm.class::isInstance).count());

        // 另一个会话 C 交给 B 的活排着队：A 的撤回不碰它
        SessionId c = new SessionId("c");
        CallModel other = world.ask(c, "go");
        world.reply(c, other, delegate("c9", "other job", false, b.value()));
        Send fromC = world.last.commands().stream().filter(Send.class::isInstance).map(Send.class::cast).findFirst().orElseThrow();
        world.deliver(fromC);
        assertEquals(1, world.session(b).inbox().waiting().size());
        world.deliver(new Send(back.id(), back.run(), b, SessionMessage.withdrawal(new tantalum.value.id.CommandId("again"), back.run())));
        assertEquals(1, world.session(b).inbox().waiting().size(), "work from another conversation stays queued");
        assertEquals(own, world.run(b));
    }

    @Test
    void takingBackNeedsTheConversationAndNothingElse() {
        World world = new World();
        CallModel call = world.ask(A, "go");
        world.reply(A, call, stop("c1", null));
        assertTrue(world.last.commands().stream().noneMatch(Send.class::isInstance));
        ToolResultBlock result = results(world, A).getFirst();
        assertTrue(result.error() && result.text().contains("\"stop\" needs \"session\""), result.text());
        CallModel next = world.only(CallModel.class);
        world.reply(A, next, stop("c2", A.value()));
        assertTrue(world.last.commands().stream().noneMatch(Send.class::isInstance));
        assertTrue(results(world, A).getFirst().error());
    }

    /**
     * “不进记忆”的会话和进记忆的会话之间不交活（新开的会话跟着交活的一方，没问题）：已有的会话标记不一样就不接，
     * 回一句没做成，那个会话一个字都不多。两个方向都一样。
     */
    @Test
    void workDoesNotCrossBetweenAnOffRecordConversationAndAnOrdinaryOne() {
        World world = new World();
        SessionId plain = new SessionId("plain");
        CallModel first = world.ask(plain, "hello");
        world.reply(plain, first, new TextBlock("hi"));
        int before = world.session(plain).messages().size();

        SessionId quiet = new SessionId("quiet");
        world.feed(quiet, null, new UserPrompt(new SubmissionId("subq"), new RequestId("reqq"), new OwnerRef.OfSession(quiet), "private thing",
                List.of(), null, null, List.of(), List.of(), true));
        assertTrue(world.session(quiet).offRecord());
        CallModel call = world.assembled(quiet, world.only(AssembleContext.class));
        world.reply(quiet, call, delegate("c1", "use the private thing", true, plain.value()));
        Send sent = world.only(Send.class);
        assertEquals(Boolean.TRUE, sent.message().offRecord());
        world.deliver(sent);
        assertEquals(before, world.session(plain).messages().size(), "nothing reached the ordinary conversation");
        Send refused = world.only(Send.class);
        assertTrue(refused.message().failed() && refused.message().text().contains("off the record"), refused.message().text());
        world.deliver(refused);
        assertTrue(results(world, quiet).getFirst().error());

        // 反过来：进记忆的会话交给已有的不进记忆的会话
        CallModel again = world.ask(plain, "ask the quiet one");
        world.reply(plain, again, delegate("c2", "what was the private thing", true, quiet.value()));
        Send other = world.last.commands().stream().filter(Send.class::isInstance).map(Send.class::cast).findFirst().orElseThrow();
        int quietBefore = world.session(quiet).messages().size();
        world.deliver(other);
        assertEquals(quietBefore, world.session(quiet).messages().size());
        assertTrue(world.only(Send.class).message().failed());
    }

    /** 一次回复里先问另一个会话（等结果）、后面跟一个独占的工具：工具当场就发，不用等到回话。 */
    @Test
    void anExclusiveToolAfterAWaitingQuestionIsSentAtOnce() {
        World world = new World();
        CallModel call = world.ask(A, "go");
        world.reply(A, call, delegate("c1", "count", true, null),
                new ToolCallBlock("c2", "write_file", "{}", new Json.Obj(Map.of("path", new Json.Str("a.txt"), "content", new Json.Str("x")))));
        assertEquals(1, world.last.commands().stream().filter(Send.class::isInstance).count());
        assertEquals(1, world.last.commands().stream().filter(tantalum.value.command.RunTool.class::isInstance).count(),
                "the tool does not wait for the other conversation: " + world.last.commands());
    }

    /**
     * 两个会话不会自己一直转下去：回话到了空闲的会话，它自己开一轮，那一轮又能交活……没有人看着。从用户最后一次说话算起，
     * 这样的轮次总共只能再交 {@link MessageSteps#MAX_UNATTENDED} 件；到了就得停下来向用户汇报。用户一说话重新算。
     */
    @Test
    void conversationsLeftAloneStopHandingWorkBackAndForth() {
        World world = new World();
        CallModel call = world.ask(A, "keep improving the report");
        world.reply(A, call, delegate("c0", "improve it", false, null));
        Send sent = world.last.commands().stream().filter(Send.class::isInstance).map(Send.class::cast).findFirst().orElseThrow();
        SessionId b = sent.to();
        assertEquals(0, world.session(A).unattended(), "the user's own turn is not counted");
        world.reply(A, world.only(CallModel.class), new TextBlock("handed off"));

        for (int round = 1; round <= MessageSteps.MAX_UNATTENDED; round++) {
            Send back = finishes(world, sent, "improved, round " + round);
            world.deliver(back);
            // A 空闲：自己开一轮处理回话，又交出去一件
            CallModel alone = world.assembled(A, world.only(AssembleContext.class));
            world.reply(A, alone, delegate("c" + round, "improve it again", false, b.value()));
            sent = world.last.commands().stream().filter(Send.class::isInstance).map(Send.class::cast).findFirst().orElseThrow();
            assertFalse(results(world, A).getFirst().error(), "round " + round);
            assertEquals(round + 1, world.session(A).unattended());
            world.reply(A, world.only(CallModel.class), new TextBlock("handed off again"));
        }
        Send back = finishes(world, sent, "improved once more");
        world.deliver(back);
        CallModel alone = world.assembled(A, world.only(AssembleContext.class));
        world.reply(A, alone, delegate("c-last", "improve it again", false, b.value()));
        assertTrue(world.last.commands().stream().noneMatch(Send.class::isInstance), "nothing more is handed off");
        ToolResultBlock refused = results(world, A).getFirst();
        assertTrue(refused.error() && refused.text().contains("since the user last spoke"), refused.text());
        world.reply(A, world.only(CallModel.class), new TextBlock("here is where things stand"));
        assertInstanceOf(Ended.class, world.run(A).phase());

        // 用户说话了：重新算
        CallModel fresh = world.ask(A, "go on then");
        assertEquals(0, world.session(A).unattended());
        world.reply(A, fresh, delegate("c-new", "improve it again", false, b.value()));
        assertEquals(1, world.last.commands().stream().filter(Send.class::isInstance).count());
        assertEquals(0, world.session(A).unattended());
    }

    private static ToolCallBlock question(String id, String text) {
        return new ToolCallBlock(id, "ask_user", "{}", new Json.Obj(Map.of("question", new Json.Str(text))));
    }

    /**
     * 接了活的会话停下来等用户回答：告诉交活的一方一声，不然那边只知道“还没回话”，用户也不知道该去哪里回答，两边一直等。
     * 等着它的那次调用当场得到“它在等用户”，这一轮接着走；那件活做完时照常有它的一句回话。一轮只说一次。
     */
    @Test
    void workThatStopsToAskTheUserTellsTheConversationThatHandedItOver() {
        World world = new World();
        Send sent = asked(world);
        SessionId b = sent.to();
        assertInstanceOf(AwaitingReply.class, world.run(A).phase());
        world.deliver(sent);
        CallModel work = world.assembled(b, world.only(AssembleContext.class));
        world.reply(b, work, question("q1", "Delete the old build directory?"));
        assertEquals(tantalum.value.state.AwaitingAnswer.NAME, world.run(b).phase().name());
        Send told = world.only(Send.class);
        assertTrue(told.message().isWaitingForUser() && !told.message().isReply() && !told.message().isRequest());
        assertEquals(sent.message().id(), told.message().answers());
        assertEquals("Delete the old build directory?", told.message().text());

        world.deliver(told);
        ToolResultBlock result = results(world, A).getFirst();
        assertFalse(result.error());
        assertTrue(result.text().contains("has stopped to ask the user") && result.text().contains("Delete the old build directory?"),
                result.text());
        assertFalse(world.run(A).phase() instanceof AwaitingReply, "the turn goes on, to tell the user");
        assertTrue(world.last.commands().stream().anyMatch(AbortCommands.class::isInstance), "the wait's timer is dropped");
        world.reply(A, world.only(CallModel.class), new TextBlock("please answer in that conversation"));
        assertInstanceOf(Ended.class, world.run(A).phase());

        // 用户在那个会话里回答了；它再问一次，不再说第二遍
        world.feed(b, world.run(b).ref().run(), new tantalum.value.event.Answered(new SubmissionId("ans1"), new RequestId("r-ans1"),
                "ask-q1", new tantalum.value.question.Answer(null, "yes")));
        world.reply(b, world.only(CallModel.class), question("q2", "And the cache too?"));
        assertTrue(world.last.commands().stream().noneMatch(Send.class::isInstance), "told once per turn");
        world.feed(b, world.run(b).ref().run(), new tantalum.value.event.Answered(new SubmissionId("ans2"), new RequestId("r-ans2"),
                "ask-q2", new tantalum.value.question.Answer(null, "no")));
        world.reply(b, world.only(CallModel.class), new TextBlock("deleted the build directory"));
        Send answer = world.only(Send.class);
        assertTrue(answer.message().isReply() && !answer.message().failed());

        // 回话照常来：已经没有调用在等，当一条消息，A 空闲就开一轮
        world.deliver(answer);
        world.assembled(A, world.only(AssembleContext.class));
        assertTrue(lastText(world, A).contains("deleted the build directory"), lastText(world, A));
    }

    /** 没在等的（不等结果的交活）：“在等人”当一条消息，会话空闲就开一轮好去告诉用户。 */
    @Test
    void theNoticeBecomesAMessageWhenNobodyIsWaitingForTheAnswer() {
        World world = new World();
        CallModel call = world.ask(A, "clean up in the background");
        world.reply(A, call, delegate("c1", "clean up", false, null));
        Send sent = world.last.commands().stream().filter(Send.class::isInstance).map(Send.class::cast).findFirst().orElseThrow();
        world.reply(A, world.only(CallModel.class), new TextBlock("started"));
        world.deliver(sent);
        world.reply(sent.to(), world.assembled(sent.to(), world.only(AssembleContext.class)), question("q1", "Delete build/?"));
        world.deliver(world.only(Send.class));
        world.assembled(A, world.only(AssembleContext.class));
        assertTrue(lastText(world, A).startsWith(DelegateCall.REPORT) && lastText(world, A).contains("Delete build/?"), lastText(world, A));
        assertEquals(1, world.session(A).unattended(), "a turn nobody asked for");
    }

    /** 重送时，“在等人”只在那一轮还等着用户的时候送；已经答了、结束了的去掉不送（旧账回放出来的也一样）。 */
    @Test
    void aStaleWaitingNoticeIsDroppedInsteadOfSentAgain() {
        World world = new World();
        Send sent = asked(world);
        SessionId b = sent.to();
        world.deliver(sent);
        world.reply(b, world.assembled(b, world.only(AssembleContext.class)), question("q1", "Delete build/?"));
        Send told = world.only(Send.class);
        assertEquals(1, world.session(b).inbox().unconfirmed().size(), "not confirmed yet");

        // 还在等：重送
        world.feed(b, null, new tantalum.value.event.Redeliver());
        assertEquals(told.message().id(), world.only(Send.class).message().id());

        // 用户答了，这一轮不再等：不送，从信箱里去掉
        world.feed(b, world.run(b).ref().run(), new tantalum.value.event.Answered(new SubmissionId("ans"), new RequestId("r-ans"),
                "ask-q1", new tantalum.value.question.Answer(null, "yes")));
        world.feed(b, null, new tantalum.value.event.Redeliver());
        assertTrue(world.last.commands().stream().noneMatch(Send.class::isInstance), world.last.commands().toString());
        assertTrue(world.session(b).inbox().unconfirmed().isEmpty());
    }

    /**
     * 活排在一轮正等着用户的后面：交活的一方得知道，不然只是“还没回话”。到的时候那一轮已经在等、或者排着队时那一轮才停下来问，
     * 都说；一句排队的话只说一次。
     */
    @Test
    void workQueuedBehindATurnThatWaitsForTheUserSaysSo() {
        World world = new World();
        SessionId b = new SessionId("b");
        CallModel own = world.ask(b, "tidy up");
        world.reply(b, own, question("q1", "Delete build/?"));
        assertEquals(tantalum.value.state.AwaitingAnswer.NAME, world.run(b).phase().name());

        // 到的时候那一轮已经在等用户
        CallModel call = world.ask(A, "count");
        world.reply(A, call, delegate("c1", "count the files", true, b.value()));
        Send sent = world.only(Send.class);
        world.deliver(sent);
        assertEquals(1, world.session(b).inbox().waiting().size(), "queued");
        Send told = world.only(Send.class);
        assertTrue(told.message().isWaitingForUser());
        assertEquals(A, told.to());
        assertEquals(sent.message().id(), told.message().answers());
        assertTrue(told.message().text().startsWith("Delete build/?") && told.message().text().contains("has not started"), told.message().text());
        world.deliver(told);
        assertTrue(results(world, A).getFirst().text().contains("has not started"), results(world, A).getFirst().text());
        assertFalse(world.run(A).phase() instanceof AwaitingReply, "A goes on and can tell the user");

        // 用户答了，那一轮又问一次：排着的那句不说第二遍
        world.feed(b, world.run(b).ref().run(), new tantalum.value.event.Answered(new SubmissionId("ans1"), new RequestId("r-ans1"),
                "ask-q1", new tantalum.value.question.Answer(null, "yes")));
        world.reply(b, world.only(CallModel.class), question("q2", "And the cache?"));
        assertTrue(world.last.commands().stream().noneMatch(Send.class::isInstance), "told once");

        // 排着队时那一轮才停下来问：也说
        World later = new World();
        CallModel busy = later.ask(b, "tidy up");
        CallModel from = later.ask(A, "count");
        later.reply(A, from, delegate("c1", "count the files", false, b.value()));
        Send queued = later.last.commands().stream().filter(Send.class::isInstance).map(Send.class::cast).findFirst().orElseThrow();
        later.deliver(queued);
        assertTrue(later.last.commands().stream().noneMatch(Send.class::isInstance), "nothing to say while it is just busy");
        later.reply(b, busy, question("q1", "Delete build/?"));
        Send notice = later.only(Send.class);
        assertTrue(notice.message().isWaitingForUser());
        assertEquals(queued.message().id(), notice.message().answers());

        // 那一轮结束，排着的活开始：信箱里“说过了”的记号跟着去掉
        later.feed(b, later.run(b).ref().run(), new tantalum.value.event.Answered(new SubmissionId("ans"), new RequestId("r-ans"),
                "ask-q1", new tantalum.value.question.Answer(null, "yes")));
        later.reply(b, later.only(CallModel.class), new TextBlock("tidied"));
        assertInstanceOf(OwnerRef.OfMessage.class, later.run(b).owner());
        assertTrue(later.session(b).inbox().told().isEmpty());
    }

    /** 撤回比活先到（活第一次没送到）：撤回带着没确认的编号，对方记成见过并回一句“没做成”；活后来到了不开工，只回确认。 */
    @Test
    void takingBackAlsoCoversWorkThatHasNotArrivedYet() {
        World world = new World();
        CallModel call = world.ask(A, "start");
        world.reply(A, call, delegate("c1", "long job", false, null));
        Send job = world.last.commands().stream().filter(Send.class::isInstance).map(Send.class::cast).findFirst().orElseThrow();
        SessionId b = job.to();
        // 这句没送到。A 接着撤回
        world.reply(A, world.only(CallModel.class), stop("c2", b.value()));
        Send back = world.only(Send.class);
        assertEquals(List.of(job.id()), back.message().unarrived());

        world.deliver(back);
        List<Send> answers = world.last.commands().stream().filter(Send.class::isInstance).map(Send.class::cast).toList();
        assertEquals(1, answers.size(), "one answer for the piece that never started");
        assertEquals(job.id(), answers.getFirst().message().answers());
        assertTrue(answers.getFirst().message().failed() && answers.getFirst().message().text().contains("before it started"));

        // 活后来补送到了：不开工，只回确认
        int runs = world.state.runs().size();
        world.deliver(job);
        assertEquals(runs, world.state.runs().size(), "it does not start");
        assertTrue(world.session(b).messages().isEmpty());
        assertTrue(world.last.commands().stream().noneMatch(Send.class::isInstance), "and does not answer twice");
        assertEquals(job.id(), world.only(tantalum.value.command.Confirm.class).message());

        // 已经到了的照旧：不重复回话
        World arrived = new World();
        CallModel first = arrived.ask(A, "start");
        arrived.reply(A, first, delegate("c1", "long job", false, null));
        Send sent = arrived.last.commands().stream().filter(Send.class::isInstance).map(Send.class::cast).findFirst().orElseThrow();
        CallModel next = arrived.only(CallModel.class);
        arrived.deliver(sent);   // 到了，确认还没回到 A
        arrived.reply(A, next, stop("c2", sent.to().value()));
        arrived.deliver(arrived.only(Send.class));
        List<Send> once = arrived.last.commands().stream().filter(Send.class::isInstance).map(Send.class::cast).toList();
        assertEquals(1, once.size(), once.toString());
        assertTrue(once.getFirst().message().text().startsWith("it was cancelled"), once.getFirst().message().text());
    }

    /** 一直没确认的话不是永远重送：到了次数就放弃。交出去的活，这个会话得到一句“没送到”；别的话记一条事实。 */
    @Test
    void aMessageThatIsNeverConfirmedIsGivenUpAndTheConversationIsTold() {
        World world = new World();
        CallModel call = world.ask(A, "start");
        world.reply(A, call, delegate("c1", "long job", false, null));
        Send job = world.last.commands().stream().filter(Send.class::isInstance).map(Send.class::cast).findFirst().orElseThrow();
        world.reply(A, world.only(CallModel.class), new TextBlock("started"));
        for (int i = 0; i < MessageSteps.MAX_REDELIVER; i++) {
            world.feed(A, null, new tantalum.value.event.Redeliver());
            assertEquals(job.id(), world.only(Send.class).message().id(), "try " + i);
        }
        world.feed(A, null, new tantalum.value.event.Redeliver());
        assertTrue(world.last.commands().stream().noneMatch(Send.class::isInstance), "not sent again");
        assertTrue(world.session(A).inbox().unconfirmed().isEmpty());
        world.assembled(A, world.only(AssembleContext.class));
        assertTrue(lastText(world, A).startsWith(DelegateCall.REPORT) && lastText(world, A).contains("was not handed over"), lastText(world, A));
        KernelState before = world.state;
        world.feed(A, null, new tantalum.value.event.Redeliver());
        assertEquals(before, world.state, "nothing left");

        // 等着回话的那种：那次调用得到“没做成”，这一轮接着走
        World waits = new World();
        Send asked = asked(waits);
        for (int i = 0; i <= MessageSteps.MAX_REDELIVER; i++) {
            waits.feed(A, null, new tantalum.value.event.Redeliver());
        }
        assertTrue(results(waits, A).getFirst().error() && results(waits, A).getFirst().text().contains("was not handed over"),
                results(waits, A).getFirst().text());
        assertFalse(waits.run(A).phase() instanceof AwaitingReply);
        assertNotNull(asked);

        // 回话送不到：记一条事实，不再送
        World answer = new World();
        Send sent = asked(answer);
        Send reply = finishes(answer, sent, "42");
        for (int i = 0; i <= MessageSteps.MAX_REDELIVER; i++) {
            answer.feed(sent.to(), null, new tantalum.value.event.Redeliver());
        }
        assertTrue(answer.session(sent.to()).inbox().unconfirmed().isEmpty());
        assertTrue(answer.last.facts().stream().anyMatch(fact -> fact.toString().contains(reply.id().value())), answer.last.facts().toString());
    }

    /** 接了活的会话正做着，用户在那里插了话：回话带上这个事实，交活的一方的模型看得到；没插话的不带。 */
    @Test
    void anAnswerSaysWhenTheUserSpokeInThatConversationWhileItWorked() {
        World world = new World();
        Send sent = asked(world);
        SessionId b = sent.to();
        world.deliver(sent);
        CallModel work = world.assembled(b, world.only(AssembleContext.class));
        world.feed(b, world.run(b).ref().run(), new tantalum.value.event.SteerSubmitted(new SubmissionId("st1"), new RequestId("r-st1"),
                "count only the java files", List.of(), List.of()));
        assertTrue(((OwnerRef.OfMessage) world.run(b).owner()).userSpoke());
        world.reply(b, work, new TextBlock("12 files"));
        // 插话在这次回答后交给模型，这一轮接着走
        world.reply(b, world.only(CallModel.class), new TextBlock("7 java files"));
        Send answer = world.last.commands().stream().filter(Send.class::isInstance).map(Send.class::cast).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, answer.message().steered());
        world.deliver(answer);
        String result = results(world, A).getFirst().text();
        assertTrue(result.startsWith("The user also wrote in conversation " + b.value()) && result.contains("7 java files"), result);

        World plain = new World();
        Send other = asked(plain);
        assertNull(finishes(plain, other, "42").message().steered());
    }
}
