package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.kernel.LoopFixture.call;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.DoWork;
import tantalum.value.command.RunTool;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.StopReason;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.KernelState;

class ToolStepsTest {

    private static void reply(LoopFixture loop, ContentBlock... calls) {
        loop.prompt("hi");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s", List.of(), null, List.of()));
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), List.of(calls), StopReason.TOOL_USE, "m", null));
    }

    private static ToolFinished finished(Command command, String text, boolean error) {
        RunTool run = (RunTool) command;
        return new ToolFinished(run.id(), run.call().id(), text, error);
    }

    @Test
    void safeCallsGoOutTogether() {
        LoopFixture loop = new LoopFixture();
        reply(loop, call("a", "safe1"), call("b", "safe2"));
        assertEquals("AwaitingTools", loop.phase());
        assertEquals(2, loop.last.commands().size());
    }

    @Test
    void resultsReturnInModelOrderEvenWhenFinishedOutOfOrder() {
        LoopFixture loop = new LoopFixture();
        reply(loop, call("a", "safe1"), call("b", "safe2"));
        List<Command> sent = loop.last.commands();
        loop.feed(loop.runId(), finished(sent.get(1), "second", false));
        assertEquals("AwaitingTools", loop.phase());
        loop.feed(loop.runId(), finished(sent.get(0), "first", false));

        assertEquals("AwaitingModel", loop.phase());
        CallModel model = assertInstanceOf(CallModel.class, loop.onlyCommand());
        Message results = model.prompt().messages().getLast();
        assertEquals(List.of(
                new ToolResultBlock("a", "first", false, null),
                new ToolResultBlock("b", "second", false, null)), results.content());
    }

    @Test
    void exclusiveCallIsSentOnlyAfterEarlierOnesFinish() {
        LoopFixture loop = new LoopFixture();
        reply(loop, call("a", "safe1"), call("b", "write"), call("c", "safe2"));
        assertEquals(1, loop.last.commands().size());
        loop.feed(loop.runId(), finished(loop.onlyCommand(), "1", false));
        assertEquals("b", ((RunTool) loop.onlyCommand()).call().id());
        loop.feed(loop.runId(), finished(loop.onlyCommand(), "2", false));
        assertEquals("c", ((RunTool) loop.onlyCommand()).call().id());
    }

    @Test
    void inFlightNeverExceedsTheLimit() {
        LoopFixture loop = new LoopFixture(2);
        reply(loop, call("a", "safe1"), call("b", "safe2"), call("c", "safe3"));
        List<Command> sent = loop.last.commands();
        assertEquals(2, sent.size());
        loop.feed(loop.runId(), finished(sent.get(0), "1", false));
        assertEquals("c", ((RunTool) loop.onlyCommand()).call().id());
    }

    @Test
    void toolErrorGoesToTheModelAndTheRunContinues() {
        LoopFixture loop = new LoopFixture();
        reply(loop, call("a", "safe1"));
        loop.feed(loop.runId(), finished(loop.onlyCommand(), "index still building", true));
        assertEquals("AwaitingModel", loop.phase());
        ToolResultBlock result = (ToolResultBlock) ((CallModel) loop.onlyCommand())
                .prompt().messages().getLast().content().getFirst();
        assertTrue(result.error());
    }

    @Test
    void invalidArgumentsBecomeAnErrorResultWithoutRunning() {
        LoopFixture loop = new LoopFixture();
        reply(loop, new ToolCallBlock("a", "safe1", "{oops", null));
        assertEquals("AwaitingModel", loop.phase());
        assertInstanceOf(CallModel.class, loop.onlyCommand());
    }

    private static KernelHook blocking(String name, HookResult result) {
        return new KernelHook() {
            @Override public String name() { return "tool"; }
            @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
                return context.call().name().equals(name) ? result : Allow.PLAIN;
            }
        };
    }

    @Test
    void blockedCallGetsAnErrorResultOthersStillRun() {
        LoopFixture loop = new LoopFixture(blocking("write", new Stop("read only")));
        reply(loop, call("a", "write"), call("b", "safe1"));
        RunTool run = assertInstanceOf(RunTool.class, loop.onlyCommand());
        assertEquals("b", run.call().id());
        loop.feed(loop.runId(), finished(run, "ok", false));
        Message results = ((CallModel) loop.onlyCommand()).prompt().messages().getLast();
        ToolResultBlock blocked = (ToolResultBlock) results.content().getFirst();
        assertTrue(blocked.error());
        assertTrue(blocked.text().contains("read only"));
    }

    @Test
    void needWorkPausesThenResumesTheToolRound() {
        LoopFixture loop = new LoopFixture(blocking("write", new NeedWork("approve", new Json.Null())));
        reply(loop, call("a", "write"), call("b", "safe1"));
        assertEquals("AwaitingWork", loop.phase());
        DoWork work = assertInstanceOf(DoWork.class, loop.onlyCommand());

        loop.feed(loop.runId(), new WorkDone(work.id(), "approve", new Json.Null(), false, null));

        assertEquals("AwaitingTools", loop.phase());
        assertEquals("a", ((RunTool) loop.onlyCommand()).call().id());
    }

    @Test
    void lateResultForAFinishedRoundChangesNothing() {
        LoopFixture loop = new LoopFixture();
        reply(loop, call("a", "safe1"));
        Command sent = loop.onlyCommand();
        loop.feed(loop.runId(), finished(sent, "1", false));
        KernelState before = loop.state;
        loop.feed(loop.runId(), finished(sent, "again", false));
        assertEquals(before, loop.state);
    }
}
