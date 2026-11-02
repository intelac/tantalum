package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.kernel.LoopFixture.call;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.Replay;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.tool.ToolPairing;
import tantalum.value.command.AssembleContext;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.RunInterrupted;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.ToolFinished;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;

/** 需求 2.1a、H9：每个等待状态下收尾后都能接着用这段历史，且不重新执行任何东西。 */
class RunInterruptedTest {

    private static final RunInterrupted EVENT = new RunInterrupted();

    private static LoopFixture atModel(KernelHook... hooks) {
        LoopFixture loop = new LoopFixture(hooks);
        loop.prompt("hi");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s", List.of(), null, List.of()));
        return loop;
    }

    /** 判定：运行以 CANCELLED 结束、详情固定、没有命令、会话空闲、每个工具调用都有配对的结果。 */
    private static void assertClosed(LoopFixture loop) {
        tantalum.value.state.Compacting compacting = loop.run().compacting();
        loop.feed(loop.runId(), EVENT);
        Ended ended = assertInstanceOf(Ended.class, loop.run().phase());
        assertEquals(EndReason.CANCELLED, ended.reason());
        assertEquals(CancelSteps.INTERRUPTED_BY_RESTART, ended.detail());
        assertEquals(List.of(), loop.last.commands());
        assertNull(loop.session().activeRun());
        assertNull(loop.run().tools());
        // 结束时 compacting 原样保留（RunEnd.finish 的规则），收尾本身不改它。
        assertEquals(compacting, loop.run().compacting());
        Set<String> calls = new HashSet<>();
        Set<String> results = new HashSet<>();
        for (Message message : loop.session().messages()) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolCallBlock toolCall) {
                    calls.add(toolCall.id());
                } else if (block instanceof ToolResultBlock result) {
                    results.add(result.toolCallId());
                }
            }
        }
        assertEquals(calls, results);
    }

    @Test
    void waitingForContext() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("hi");
        assertEquals("AwaitingContext", loop.phase());
        assertClosed(loop);
    }

    @Test
    void waitingForTheModel() {
        LoopFixture loop = atModel();
        assertEquals("AwaitingModel", loop.phase());
        assertClosed(loop);
    }

    @Test
    void waitingForARetryTimer() {
        LoopFixture loop = atModel();
        loop.feed(loop.runId(), new ModelFailed(loop.onlyCommandId(), FailureReason.SERVER_ERROR, true, Duration.ofSeconds(1), "", "500"));
        assertEquals("AwaitingRetry", loop.phase());
        assertClosed(loop);
    }

    @Test
    void waitingForToolsWithOneDoneAndOthersNot() {
        LoopFixture loop = atModel();
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(),
                List.of(call("a", "safe1"), call("b", "safe2"), call("c", "write")), StopReason.TOOL_USE, "m", null));
        assertEquals("AwaitingTools", loop.phase());
        loop.feed(loop.runId(), new ToolFinished(loop.last.commands().getFirst().id(), "a", "done", false));
        assertClosed(loop);

        List<ContentBlock> results = loop.session().messages().getLast().content();
        assertEquals(ToolPairing.OUTCOME_UNKNOWN, code(results, "b"), "was running when the process died: it may have taken effect");
        assertEquals(ToolPairing.ABORTED_BEFORE_DISPATCH, code(results, "c"), "was never sent");
    }

    private static String code(List<ContentBlock> results, String call) {
        return results.stream().map(ToolResultBlock.class::cast).filter(result -> result.toolCallId().equals(call))
                .findFirst().orElseThrow().code();
    }

    @Test
    void waitingForHookWork() {
        LoopFixture loop = atModel(new KernelHook() {
            @Override public String name() { return "failure"; }
            @Override public HookResult onFailure(KernelState state, HookContext context) { return new NeedWork("compact", new Json.Null()); }
        });
        loop.feed(loop.runId(), new ModelFailed(loop.onlyCommandId(), FailureReason.CONTEXT_OVERFLOW, false, null, "", ""));
        assertEquals("AwaitingWork", loop.phase());
        assertClosed(loop);
    }

    @Test
    void waitingForASummary() {
        LoopFixture loop = CompactStepsTest.overThreshold();
        assertEquals("AwaitingSummary", loop.phase());
        assertClosed(loop);
    }

    @Test
    void unreadSteerIsKeptForTheNextRun() {
        LoopFixture loop = atModel();
        loop.feed(loop.runId(), new SteerSubmitted(new SubmissionId("s"), new RequestId("r"), "also this"));
        assertClosed(loop);
        Message last = loop.session().messages().getLast();
        assertEquals(Role.USER, last.role());
        assertTrue(last.content().toString().contains("also this"), last.toString());
    }

    @Test
    void queuedFollowUpStartsAndThatIsTheOnlyCommand() {
        LoopFixture loop = atModel();
        RunId first = loop.runId();
        loop.prompt("next");
        loop.feed(first, EVENT);
        assertEquals(EndReason.CANCELLED, assertInstanceOf(Ended.class, loop.state.runs().get(first).phase()).reason());
        assertInstanceOf(AssembleContext.class, loop.onlyCommand());
    }

    @Test
    void anEndedRunIsLeftAlone() {
        LoopFixture loop = atModel();
        RunId run = loop.runId();
        loop.feed(run, EVENT);
        KernelState before = loop.state;
        loop.feed(run, EVENT);
        assertSame(before, loop.state);
    }

    @Test
    void lateResultAfterTheInterruptionChangesNothing() {
        LoopFixture loop = atModel();
        RunId run = loop.runId();
        var waiting = loop.onlyCommandId();
        loop.feed(run, EVENT);
        KernelState before = loop.state;
        loop.feed(run, new ModelReplied(waiting, LoopFixture.text("late"), StopReason.END_TURN, "m", null));
        assertSame(before, loop.state);
    }

    @Test
    void replayGivesTheSameState() {
        LoopFixture loop = atModel();
        loop.feed(loop.runId(), EVENT);
        assertEquals(loop.state, Replay.run(loop.kernel, loop.journal).state());
    }
}
