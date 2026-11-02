package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.command.CallModel;
import tantalum.value.command.DoWork;
import tantalum.value.command.StartTimer;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.TimerFired;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.json.Json;
import tantalum.value.model.FailureReason;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;

class RetryStepsTest {

    private static LoopFixture atModel(KernelHook... hooks) {
        LoopFixture loop = new LoopFixture(hooks);
        loop.prompt("hi");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s", List.of(), null, List.of()));
        return loop;
    }

    private static ModelFailed retryable(LoopFixture loop, Duration after) {
        return new ModelFailed(loop.onlyCommandId(), FailureReason.SERVER_ERROR, true, after, "", "500");
    }

    private static KernelHook onFailure(HookResult result) {
        return new KernelHook() {
            @Override public String name() { return "failure"; }
            @Override public HookResult onFailure(KernelState state, HookContext context) { return result; }
        };
    }

    @Test
    void retryableFailureWaitsThenCallsTheModelAgain() {
        LoopFixture loop = atModel();
        loop.feed(loop.runId(), retryable(loop, null));
        assertEquals("AwaitingRetry", loop.phase());
        assertInstanceOf(StartTimer.class, loop.onlyCommand());

        loop.feed(loop.runId(), new TimerFired(loop.onlyCommandId()));
        assertEquals("AwaitingModel", loop.phase());
        assertInstanceOf(CallModel.class, loop.onlyCommand());
    }

    @Test
    void backoffGrowsAndRespectsRetryAfter() {
        LoopFixture loop = atModel();
        loop.feed(loop.runId(), retryable(loop, null));
        assertEquals(Duration.ofSeconds(1), ((StartTimer) loop.onlyCommand()).delay());
        loop.feed(loop.runId(), new TimerFired(loop.onlyCommandId()));
        loop.feed(loop.runId(), retryable(loop, Duration.ofSeconds(30)));
        assertEquals(Duration.ofSeconds(30), ((StartTimer) loop.onlyCommand()).delay());
        loop.feed(loop.runId(), new TimerFired(loop.onlyCommandId()));
        loop.feed(loop.runId(), retryable(loop, Duration.ofSeconds(1)));
        assertEquals(Duration.ofSeconds(4), ((StartTimer) loop.onlyCommand()).delay());
    }

    @Test
    void fourthFailureEndsTheRun() {
        LoopFixture loop = atModel();
        for (int i = 0; i < 3; i++) {
            loop.feed(loop.runId(), retryable(loop, null));
            loop.feed(loop.runId(), new TimerFired(loop.onlyCommandId()));
        }
        loop.feed(loop.runId(), retryable(loop, null));
        assertEquals(EndReason.FAILED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
    }

    @Test
    void contextOverflowAndRefusalAreNotRetried() {
        for (FailureReason reason : List.of(FailureReason.CONTEXT_OVERFLOW, FailureReason.REFUSAL)) {
            LoopFixture loop = atModel();
            loop.feed(loop.runId(), new ModelFailed(loop.onlyCommandId(), reason, false, null, "", reason.name()));
            Ended ended = assertInstanceOf(Ended.class, loop.run().phase());
            assertEquals(EndReason.FAILED, ended.reason());
            assertEquals(reason.name(), ended.detail());
        }
    }

    @Test
    void hookStopEndsAsFailed() {
        LoopFixture loop = atModel(onFailure(new Stop("give up")));
        loop.feed(loop.runId(), retryable(loop, null));
        assertEquals(EndReason.FAILED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
    }

    @Test
    void needWorkRecoversEvenWhenNotRetryable() {
        LoopFixture loop = atModel(onFailure(new NeedWork("compact", new Json.Null())));
        loop.feed(loop.runId(), new ModelFailed(loop.onlyCommandId(), FailureReason.CONTEXT_OVERFLOW, false, null, "", ""));
        assertEquals("AwaitingWork", loop.phase());
        DoWork work = assertInstanceOf(DoWork.class, loop.onlyCommand());

        loop.feed(loop.runId(), new WorkDone(work.id(), "compact", new Json.Null(), false, null));
        assertEquals("AwaitingModel", loop.phase());
    }

    /** 失败钩子每次都申请工作（如压缩），失败持续时不能无限循环：工作后的再调用也占重试次数。 */
    @Test
    void workFromFailureCountsAsARetryAndEventuallyFails() {
        LoopFixture loop = atModel(onFailure(new NeedWork("compact", new Json.Null())));
        for (int i = 0; i < RetrySteps.MAX_RETRIES; i++) {
            loop.feed(loop.runId(), retryable(loop, null));
            assertEquals("AwaitingWork", loop.phase(), "round " + i);
            loop.feed(loop.runId(), new WorkDone(loop.onlyCommandId(), "compact", new Json.Null(), false, null));
            assertEquals("AwaitingModel", loop.phase(), "round " + i);
            assertEquals(i + 1, loop.run().retries());
        }
        loop.feed(loop.runId(), retryable(loop, null));
        loop.feed(loop.runId(), new WorkDone(loop.onlyCommandId(), "compact", new Json.Null(), false, null));
        assertEquals(EndReason.FAILED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
    }

    @Test
    void continueTextAfterFailureWorkGoesToTheModelAndCounts() {
        LoopFixture loop = atModel(onFailure(new NeedWork("compact", new Json.Null())));
        loop.feed(loop.runId(), retryable(loop, null));
        loop.feed(loop.runId(), new WorkDone(loop.onlyCommandId(), "compact", new Json.Null(), false, "summarised"));
        CallModel call = assertInstanceOf(CallModel.class, loop.onlyCommand());
        assertEquals(tantalum.value.model.Message.user("summarised"), call.prompt().messages().getLast());
        assertEquals(1, loop.run().retries());
    }

    @Test
    void retryAfterIsCapped() {
        assertEquals(RetrySteps.MAX_BACKOFF, RetrySteps.backoff(0, Duration.ofHours(1)));
        assertEquals(Duration.ofSeconds(30), RetrySteps.backoff(0, Duration.ofSeconds(30)));
    }

    @Test
    void replyResetsTheRetryCount() {
        LoopFixture loop = atModel();
        loop.feed(loop.runId(), retryable(loop, null));
        assertEquals(1, loop.run().retries());
        loop.feed(loop.runId(), new TimerFired(loop.onlyCommandId()));
        loop.feed(loop.runId(), new tantalum.value.event.ModelReplied(loop.onlyCommandId(),
                List.of(LoopFixture.call("a", "safe")), tantalum.value.model.StopReason.TOOL_USE, "m", null));
        assertEquals(0, loop.run().retries());
    }
}
