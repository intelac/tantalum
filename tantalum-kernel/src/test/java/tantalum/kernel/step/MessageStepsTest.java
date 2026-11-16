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
            kernel = new Kernel(table, new HookChain(List.of()), (tool, args) -> true, Limits.DEFAULT);
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
}
