package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static tantalum.kernel.LoopFixture.call;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.tool.ToolPairing;
import tantalum.value.command.AbortCommands;
import tantalum.value.command.AssembleContext;
import tantalum.value.event.Cancel;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ToolFinished;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;

class CancelStepsTest {

    private static final Cancel CANCEL = new Cancel(new RequestId("c"));

    @Test
    void cancelEndsTheRunAndAbortsInFlightCommands() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("hi");
        var waiting = loop.onlyCommandId();
        loop.feed(loop.runId(), CANCEL);

        assertEquals(EndReason.CANCELLED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
        assertEquals(Set.of(waiting), assertInstanceOf(AbortCommands.class, loop.onlyCommand()).commands());
    }

    @Test
    void unanweredToolCallsArePairedWithSyntheticResults() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("hi");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s", List.of(), null, List.of()));
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(),
                List.of(call("a", "safe1"), call("b", "write")), StopReason.TOOL_USE, "m", null, null, null, null));
        RunId run = loop.runId();

        loop.feed(run, CANCEL);

        Message results = loop.session().messages().getLast();
        assertEquals(Role.USER, results.role());
        assertEquals(ToolPairing.OUTCOME_UNKNOWN, ((ToolResultBlock) results.content().get(0)).code());
        assertEquals(ToolPairing.ABORTED_BEFORE_DISPATCH, ((ToolResultBlock) results.content().get(1)).code());
    }

    @Test
    void lateToolResultAfterCancelDoesNotChangeHistory() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("hi");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s", List.of(), null, List.of()));
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(),
                List.of(call("a", "safe1")), StopReason.TOOL_USE, "m", null, null, null, null));
        var sent = loop.onlyCommandId();
        RunId run = loop.runId();
        loop.feed(run, CANCEL);
        KernelState before = loop.state;

        loop.feed(run, new ToolFinished(sent, "a", "late", false, null));

        assertSame(before, loop.state);
    }

    @Test
    void cancellingAnEndedRunChangesNothing() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("hi");
        RunId run = loop.runId();
        loop.feed(run, CANCEL);
        KernelState before = loop.state;
        loop.feed(run, CANCEL);
        assertSame(before, loop.state);
    }

    @Test
    void cancelWithoutARunChangesNothing() {
        LoopFixture loop = new LoopFixture();
        KernelState before = loop.state;
        loop.feed(null, CANCEL);
        assertSame(before, loop.state);
    }

    @Test
    void droppedCommandFailsTheRun() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("hi");
        RunId run = loop.runId();
        loop.feed(run, new tantalum.value.event.CommandDropped(loop.onlyCommandId(), "output ring full"));
        Ended ended = assertInstanceOf(Ended.class, loop.run().phase());
        assertEquals(EndReason.FAILED, ended.reason());
        assertEquals("output ring full", ended.detail());
    }

    @Test
    void queuedFollowUpStartsAfterCancel() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("first");
        RunId first = loop.runId();
        loop.prompt("second");
        loop.feed(first, CANCEL);

        assertEquals("AwaitingContext", loop.phase());
        assertEquals(2, loop.last.commands().size());
        assertInstanceOf(AbortCommands.class, loop.last.commands().get(0));
        assertInstanceOf(AssembleContext.class, loop.last.commands().get(1));
    }
}
