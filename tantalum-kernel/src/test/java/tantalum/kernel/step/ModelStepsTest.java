package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.kernel.LoopFixture.text;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.DoWork;
import tantalum.value.decision.PhaseChanged;
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
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.StopReason;
import tantalum.value.model.TokenUsage;
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
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of()));
    }

    private static ModelReplied reply(LoopFixture loop, String text) {
        return new ModelReplied(loop.onlyCommandId(), text(text), StopReason.END_TURN, "m", null);
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

    @Test
    void beforeModelCallPatchReachesThePrompt() {
        LoopFixture loop = new LoopFixture(beforeModel(new Allow(new PromptPatch("+x", List.of()))));
        toModel(loop);
        assertEquals("system+x", assertInstanceOf(CallModel.class, loop.onlyCommand()).prompt().system());
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
                new TokenUsage(5, 1, 0, 0)));
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
        loop.feed(loop.runId(), new ModelReplied(waiting, text("x"), StopReason.END_TURN, "m", null));

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

    @Test
    void interruptedTextJoinsTheSessionWithoutChangingTheRun() {
        LoopFixture loop = new LoopFixture();
        toModel(loop);
        var run = loop.run();
        loop.feed(run.ref().run(), new ModelInterrupted(new CommandId("old"), "partial"));
        Message message = loop.session().messages().getLast();
        assertTrue(message.interrupted());
        assertEquals(text("partial"), message.content());
        assertEquals("AwaitingModel", loop.phase());
    }

    @Test
    void emptyInterruptedTextAddsNothing() {
        LoopFixture loop = new LoopFixture();
        toModel(loop);
        loop.feed(loop.runId(), new ModelInterrupted(new CommandId("old"), ""));
        assertEquals(1, loop.session().messages().size());
    }
}
