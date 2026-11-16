package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
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
import tantalum.value.command.CancelDelegate;
import tantalum.value.command.Command;
import tantalum.value.command.Delegate;
import tantalum.value.command.Return;
import tantalum.value.command.RunTool;
import tantalum.value.command.StartTimer;
import tantalum.value.decision.Decision;
import tantalum.value.event.Cancel;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.DelegateReturned;
import tantalum.value.event.Delegated;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.Recovered;
import tantalum.value.event.RunInterrupted;
import tantalum.value.event.TimerFired;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.AwaitingDelegate;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/**
 * kernel-delegation D2：委派在内核里的每一步。两个会话在同一棵状态树里，测试自己当“邮递员”：把内核发的交活、交回命令变成
 * 对方会话的事件。没有线程、没有时钟，一切由事件推动；同一串事件再喂一遍结果相同。
 */
class DelegateStepsTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId PARENT = new SessionId("p");
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
            RunId run = session.activeRun() != null ? session.activeRun() : session.lastRun();
            return state.runs().get(run);
        }

        <T extends Command> T only(Class<T> type) {
            List<T> found = last.commands().stream().filter(type::isInstance).map(type::cast).toList();
            assertEquals(1, found.size(), type.getSimpleName() + " in " + last.commands());
            return found.getFirst();
        }

        /** 提问到模型第一次被调用：返回那次调用的命令。 */
        CallModel ask(SessionId session, String text) {
            feed(session, null, new UserPrompt(new SubmissionId("sub" + seq), new RequestId("req" + seq), new OwnerRef.OfSession(session), text));
            return assembled(session);
        }

        CallModel assembled(SessionId session) {
            return assembled(session, only(AssembleContext.class));
        }

        /** 上下文组装好了；{@code start} 是那一轮开始时发的组装命令（中间又喂过别的事件时要自己留着）。 */
        CallModel assembled(SessionId session, AssembleContext start) {
            feed(session, run(session).ref().run(), new ContextAssembled(start.id(), "sys", List.of(), null, List.of()));
            return only(CallModel.class);
        }

        Decision reply(SessionId session, CallModel call, ContentBlock... content) {
            boolean tools = List.of(content).stream().anyMatch(ToolCallBlock.class::isInstance);
            return feed(session, run(session).ref().run(), new ModelReplied(call.id(), List.of(content),
                    tools ? StopReason.TOOL_USE : StopReason.END_TURN, "m", null, null, null, null));
        }

        /** 邮递员：把一条交活送进子会话。 */
        Decision deliver(Delegate command) {
            return feed(command.child(), null, command.handoff());
        }

        /** 邮递员：把一条交回送进父会话。 */
        Decision deliver(Return command) {
            return feed(command.parent(), null, command.outcome());
        }
    }

    private static ToolCallBlock delegate(String id, String task, boolean wait) {
        return new ToolCallBlock(id, "delegate", "{}", new Json.Obj(Map.of("task", new Json.Str(task), "wait", new Json.Bool(wait))));
    }

    private static String lastToolResult(World world, SessionId session) {
        List<tantalum.value.model.Message> messages = world.session(session).messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            for (ContentBlock block : messages.get(i).content()) {
                if (block instanceof ToolResultBlock result) {
                    return (result.error() ? "ERROR " : "") + result.text();
                }
            }
        }
        return null;
    }

    /** 父会话交一件活并等着：到“等委派”为止，返回那条交活命令。 */
    private static Delegate handedOff(World world) {
        CallModel call = world.ask(PARENT, "count the files");
        world.reply(PARENT, call, delegate("c1", "count the files in src", true));
        return world.only(Delegate.class);
    }

    /** 子会话把这件活做完：返回交回命令。 */
    private static Return childFinishes(World world, Delegate handoff, String answer) {
        world.deliver(handoff);
        CallModel call = world.assembled(handoff.child());
        world.reply(handoff.child(), call, new TextBlock(answer));
        return world.only(Return.class);
    }

    @Test
    void handingOffWaitsInAStateNotInAThread() {
        World world = new World();
        Delegate handoff = handedOff(world);
        assertEquals("d-" + handoff.id().value(), handoff.child().value(), "the child's id comes from the delegation id");
        assertEquals(handoff.id(), handoff.handoff().delegation());
        assertEquals(PARENT, handoff.handoff().from().session());
        assertEquals(1, handoff.handoff().depth());
        assertTrue(handoff.handoff().text().contains("count the files in src"));
        RunState run = world.run(PARENT);
        AwaitingDelegate waiting = assertInstanceOf(AwaitingDelegate.class, run.phase());
        assertEquals(AT.plus(DelegateSteps.LIMIT), waiting.deadline());
        assertEquals(waiting.timer(), world.only(StartTimer.class).id());
        assertTrue(world.last.commands().stream().noneMatch(RunTool.class::isInstance), "delegate is not run as a tool");
        assertEquals(List.of(handoff.id()), world.session(PARENT).handoffs().open().stream().map(sent -> sent.handoff().delegation()).toList());
    }

    @Test
    void theChildRunsOwnedByTheHandOffAndHandsItsAnswerBack() {
        World world = new World();
        Delegate handoff = handedOff(world);
        world.deliver(handoff);
        SessionState child = world.session(handoff.child());
        assertEquals(PARENT, child.handoffs().parent());
        assertEquals(1, child.handoffs().depth());
        assertEquals(new OwnerRef.OfCommand(handoff.id()), world.run(handoff.child()).owner());
        CallModel call = world.assembled(handoff.child());
        world.reply(handoff.child(), call, new TextBlock("42 files"));
        Return back = world.only(Return.class);
        assertEquals(PARENT, back.parent());
        assertEquals(DelegateReturned.Kind.RESULT, back.outcome().kind());
        assertEquals("42 files", back.outcome().text());
        assertEquals(handoff.id(), back.outcome().delegation());

        world.deliver(back);
        assertEquals("Result from sub-conversation " + handoff.child().value() + ":\n42 files", lastToolResult(world, PARENT));
        world.only(CallModel.class);
        assertTrue(world.last.commands().stream().anyMatch(AbortCommands.class::isInstance), "the deadline timer is not needed any more");
        assertTrue(world.session(PARENT).handoffs().open().isEmpty());
    }

    @Test
    void aSecondCopyOfTheReturnIsIgnored() {
        World world = new World();
        Delegate handoff = handedOff(world);
        Return back = childFinishes(world, handoff, "42 files");
        world.deliver(back);
        int messages = world.session(PARENT).messages().size();
        world.deliver(back);
        assertTrue(world.last.commands().isEmpty());
        assertEquals(messages, world.session(PARENT).messages().size());
    }

    @Test
    void twoHandOffsInOneReplyRunSideBySideAndKeepTheModelsOrder() {
        World world = new World();
        CallModel call = world.ask(PARENT, "two things");
        world.reply(PARENT, call, delegate("c1", "first", true), delegate("c2", "second", true));
        List<Delegate> handoffs = world.last.commands().stream().filter(Delegate.class::isInstance).map(Delegate.class::cast).toList();
        assertEquals(2, handoffs.size());
        Return second = childFinishes(world, handoffs.get(1), "B");
        world.deliver(second);
        assertInstanceOf(AwaitingDelegate.class, world.run(PARENT).phase(), "still waiting for the first");
        assertTrue(world.last.commands().isEmpty());
        Return first = childFinishes(world, handoffs.get(0), "A");
        world.deliver(first);
        world.only(CallModel.class);
        List<String> results = world.session(PARENT).messages().getLast().content().stream()
                .map(block -> ((ToolResultBlock) block).text()).toList();
        assertTrue(results.get(0).endsWith("A") && results.get(1).endsWith("B"), results.toString());
    }

    @Test
    void aHandOffNextToAnOrdinaryToolWaitsForBoth() {
        World world = new World();
        CallModel call = world.ask(PARENT, "mixed");
        world.reply(PARENT, call, new ToolCallBlock("t1", "read_file", "{}", new Json.Obj(Map.of())), delegate("c2", "side job", true));
        RunTool tool = world.only(RunTool.class);
        Delegate handoff = world.only(Delegate.class);
        assertEquals(ToolSteps.AWAITING_TOOLS, world.run(PARENT).phase().name());
        Return back = childFinishes(world, handoff, "done");
        world.deliver(back);
        assertEquals(ToolSteps.AWAITING_TOOLS, world.run(PARENT).phase().name(), "the tool is still running");
        world.feed(PARENT, world.run(PARENT).ref().run(), new ToolFinished(tool.id(), "t1", "file text", false, null, null));
        world.only(CallModel.class);
    }

    @Test
    void withoutWaitingTheSlotIsDoneAtOnceAndTheAnswerComesAsAReport() {
        World world = new World();
        CallModel call = world.ask(PARENT, "long job");
        world.reply(PARENT, call, delegate("c1", "a long job", false));
        Delegate handoff = world.only(Delegate.class);
        CallModel next = world.only(CallModel.class);
        assertTrue(lastToolResult(world, PARENT).startsWith("Started sub-conversation " + handoff.child().value()));
        world.reply(PARENT, next, new TextBlock("I started it."));
        assertInstanceOf(Ended.class, world.run(PARENT).phase());

        Return back = childFinishes(world, handoff, "the long job is done");
        world.deliver(back);
        assertNull(world.session(PARENT).activeRun() == null ? "idle" : null, "the report woke the parent: a new run started");
        assertEquals(new OwnerRef.OfSession(PARENT), world.run(PARENT).owner());
        String report = ((TextBlock) world.session(PARENT).messages().getLast().content().getFirst()).text();
        assertTrue(report.startsWith("Report from a sub-conversation") && report.contains("the long job is done"), report);
        world.only(AssembleContext.class);
        assertTrue(world.session(PARENT).handoffs().open().isEmpty());
    }

    @Test
    void aReportWhileTheParentIsBusyBecomesASteer() {
        World world = new World();
        CallModel call = world.ask(PARENT, "long job");
        world.reply(PARENT, call, delegate("c1", "a long job", false));
        Delegate handoff = world.only(Delegate.class);
        Return back = childFinishes(world, handoff, "done early");
        world.deliver(back);
        assertEquals(1, world.run(PARENT).pendingSteers().size(), "the parent run is still going: it sees the report at its next step");
        assertTrue(world.last.commands().isEmpty());
    }

    @Test
    void cancellingTheParentTellsTheChildToStopAndItsLateReturnDoesNotCount() {
        World world = new World();
        Delegate handoff = handedOff(world);
        world.deliver(handoff);
        world.feed(PARENT, null, new Cancel(new RequestId("x")));
        assertEquals(List.of(new CancelDelegate.Target(handoff.child(), handoff.id())), world.only(CancelDelegate.class).targets());
        assertTrue(world.last.commands().stream().anyMatch(AbortCommands.class::isInstance));
        assertEquals(EndReason.CANCELLED, ((Ended) world.run(PARENT).phase()).reason());
        assertTrue(lastToolResult(world, PARENT).contains("told to stop"));

        // 邮递员把“父会话不等了”送进子会话：只停这次交活开的运行
        world.feed(handoff.child(), null, new tantalum.value.event.DelegateCancelled(new tantalum.value.id.CommandId("someone-else")));
        assertNull(world.run(handoff.child()).phase() instanceof Ended ? "ended" : null, "another delegation's cancel does not stop it");
        world.feed(handoff.child(), null, new tantalum.value.event.DelegateCancelled(handoff.id()));
        assertEquals(DelegateSteps.STOPPED_BY_PARENT, ((Ended) world.run(handoff.child()).phase()).detail());
        Return back = world.only(Return.class);
        assertEquals(DelegateReturned.Kind.CANCELLED, back.outcome().kind());
        int messages = world.session(PARENT).messages().size();
        world.deliver(back);
        assertEquals(messages, world.session(PARENT).messages().size(), "nothing is waiting for it any more");
        assertNull(world.session(PARENT).activeRun());
    }

    @Test
    void theDeadlineEndsTheWaitAndStopsTheChild() {
        World world = new World();
        Delegate handoff = handedOff(world);
        AwaitingDelegate waiting = (AwaitingDelegate) world.run(PARENT).phase();
        world.feed(PARENT, world.run(PARENT).ref().run(), new TimerFired(waiting.timer()));
        assertEquals(List.of(new CancelDelegate.Target(handoff.child(), handoff.id())), world.only(CancelDelegate.class).targets());
        world.only(CallModel.class);
        assertTrue(lastToolResult(world, PARENT).startsWith("ERROR The sub-conversation did not finish within 10 minutes"));
    }

    @Test
    void aChildThatFailsOrIsInterruptedSaysSo() {
        World world = new World();
        Delegate handoff = handedOff(world);
        world.deliver(handoff);
        world.feed(handoff.child(), world.run(handoff.child()).ref().run(), new RunInterrupted());
        Return back = world.only(Return.class);
        assertEquals(DelegateReturned.Kind.INTERRUPTED, back.outcome().kind());
        world.deliver(back);
        String result = lastToolResult(world, PARENT);
        assertTrue(result.startsWith("ERROR") && result.contains("interrupted by a server restart")
                && result.contains("session=\"" + handoff.child().value() + "\""), result);
    }

    @Test
    void afterARestartTheHandOffIsSentAgainAndNothingIsDoneTwice() {
        World world = new World();
        Delegate handoff = handedOff(world);
        // 重启时父在等委派：不被打断
        world.feed(PARENT, world.run(PARENT).ref().run(), new RunInterrupted());
        assertInstanceOf(AwaitingDelegate.class, world.run(PARENT).phase());
        // 情况一：交活没到过子会话。恢复后重发，子会话照常开工
        world.feed(PARENT, null, new Recovered());
        Delegate again = world.only(Delegate.class);
        assertEquals(handoff, again);
        world.deliver(again);
        AssembleContext start = world.only(AssembleContext.class);
        assertEquals(new OwnerRef.OfCommand(handoff.id()), world.run(handoff.child()).owner());
        // 情况二：子会话正在做时又收到一次：不动
        int before = world.session(handoff.child()).messages().size();
        world.deliver(again);
        assertTrue(world.last.commands().isEmpty());
        assertEquals(before, world.session(handoff.child()).messages().size());
        // 情况三：子会话做完了、交回丢了。再收到交活时把同样的交回再发一次
        CallModel call = world.assembled(handoff.child(), start);
        world.reply(handoff.child(), call, new TextBlock("42 files"));
        Return lost = world.only(Return.class);
        world.deliver(again);
        Return resent = world.only(Return.class);
        assertEquals(lost.outcome(), resent.outcome());
        assertEquals(before + 1, world.session(handoff.child()).messages().size(), "the work was not done again");
        world.deliver(resent);
        assertTrue(lastToolResult(world, PARENT).endsWith("42 files"));
    }

    @Test
    void aBusyChildQueuesTheNextHandOff() {
        World world = new World();
        CallModel call = world.ask(PARENT, "start");
        world.reply(PARENT, call, delegate("c1", "first job", false));
        Delegate first = world.only(Delegate.class);
        CallModel next = world.only(CallModel.class);
        world.deliver(first);
        AssembleContext start = world.only(AssembleContext.class);
        ToolCallBlock more = new ToolCallBlock("c2", "delegate", "{}", new Json.Obj(Map.of("task", new Json.Str("second job"),
                "session", new Json.Str(first.child().value()))));
        world.reply(PARENT, next, more);
        Delegate second = world.only(Delegate.class);
        assertEquals(first.child(), second.child());
        assertEquals(Delegated.Kind.CONTINUE, second.handoff().kind());
        world.deliver(second);
        assertEquals(List.of(second.handoff()), world.session(first.child()).handoffs().queued());

        CallModel childCall = world.assembled(first.child(), start);
        world.reply(first.child(), childCall, new TextBlock("first done"));
        Return firstBack = world.only(Return.class);
        assertEquals(first.id(), firstBack.outcome().delegation());
        assertEquals(new OwnerRef.OfCommand(second.id()), world.run(first.child()).owner(), "the queued hand-off started");
        assertTrue(world.session(first.child()).handoffs().queued().isEmpty());
    }

    @Test
    void limitsAndBadArgumentsAnswerTheModelWithoutHandingOff() {
        World world = new World();
        CallModel call = world.ask(PARENT, "bad");
        world.reply(PARENT, call, new ToolCallBlock("c1", "delegate", "{}", new Json.Obj(Map.of())),
                new ToolCallBlock("c2", "delegate", "{}", new Json.Obj(Map.of("task", new Json.Str("x"), "session", new Json.Str("s-other")))));
        assertTrue(world.last.commands().stream().noneMatch(Delegate.class::isInstance));
        world.only(CallModel.class);
        List<ToolResultBlock> results = world.session(PARENT).messages().getLast().content().stream().map(ToolResultBlock.class::cast).toList();
        assertTrue(results.get(0).error() && results.get(0).text().contains("\"task\""), results.get(0).text());
        assertTrue(results.get(1).error() && results.get(1).text().contains("sub-conversation that this conversation started"));
    }

    @Test
    void theThirdLevelIsRefused() {
        World world = new World();
        Delegate first = handedOff(world);
        world.deliver(first);
        CallModel childCall = world.assembled(first.child());
        world.reply(first.child(), childCall, delegate("c1", "deeper", true));
        Delegate second = world.only(Delegate.class);
        assertEquals(2, second.handoff().depth());
        world.deliver(second);
        CallModel grandCall = world.assembled(second.child());
        world.reply(second.child(), grandCall, delegate("c1", "too deep", true));
        assertTrue(world.last.commands().stream().noneMatch(Delegate.class::isInstance));
        assertTrue(lastToolResult(world, second.child()).startsWith("ERROR This conversation was itself delegated 2 levels deep"));
    }

    @Test
    void aHandOffFromAStrangerOrIntoAnOrdinaryConversationIsRefused() {
        World world = new World();
        Delegate handoff = handedOff(world);
        SessionId ordinary = new SessionId("o");
        world.ask(ordinary, "my own conversation");
        world.feed(ordinary, null, handoff.handoff());
        Return refused = world.only(Return.class);
        assertEquals(DelegateReturned.Kind.REFUSED, refused.outcome().kind());
        assertEquals(1, world.session(ordinary).messages().size(), "the ordinary conversation is untouched");
        assertFalse(world.session(ordinary).handoffs().isChild());
    }

    @Test
    void feedingTheSameEventsAgainGivesTheSameStateAndCommands() {
        World world = new World();
        Delegate handoff = handedOff(world);
        Return back = childFinishes(world, handoff, "42 files");
        world.deliver(back);
        World replay = new World();
        for (EventEnvelope envelope : world.journal) {
            replay.feed(envelope.session(), envelope.run(), envelope.event());
        }
        assertEquals(world.state, replay.state);
        assertEquals(world.commands, replay.commands);
        assertEquals(Role.USER, world.session(PARENT).messages().getLast().role());
    }
}
