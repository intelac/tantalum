package tantalum.shell.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.shell.conversation.TranscriptFixture.badCall;
import static tantalum.shell.conversation.TranscriptFixture.call;
import static tantalum.shell.conversation.TranscriptFixture.text;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.tool.ToolPairing;
import tantalum.shell.conversation.TranscriptItem.StepStatus;
import tantalum.shell.conversation.TranscriptItem.ToolStep;
import tantalum.value.event.Cancel;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.state.EndReason;
import tantalum.value.state.KernelState;

/** 工具链：并行分组、独占、等待中、拦截、无效参数、合成错误、耗时。 */
class TranscriptToolTest {

    private static final KernelHook NO_RM = new KernelHook() {
        @Override
        public String name() {
            return "no-rm";
        }

        @Override
        public HookResult beforeToolCall(KernelState state, HookContext context) {
            return context.call().name().equals("rm") ? new Stop("destructive") : KernelHook.super.beforeToolCall(state, context);
        }
    };

    @Test
    void parallelSafeCallsFormOneGroupAndEachGetsItsOwnDuration() {
        TranscriptFixture fx = new TranscriptFixture(true, List.of());
        RunId run = fx.start("hi");
        Instant replyAt = fx.now();
        fx.feed(run, fx.reply(call("c1", "read_file"), call("c2", "read_file"), call("c3", "grep")));

        List<ToolStep> steps = fx.items(ToolStep.class);
        assertEquals(3, steps.size());
        assertTrue(steps.stream().allMatch(step -> step.status() == StepStatus.RUNNING));
        assertEquals(1, steps.stream().map(ToolStep::group).distinct().count());
        assertTrue(steps.stream().allMatch(step -> replyAt.equals(step.startedAt())));

        fx.tick(Duration.ofSeconds(4));
        fx.feed(run, fx.finished(run, "c2", "b", false));
        fx.feed(run, fx.finished(run, "c1", "a", false));

        assertEquals(StepStatus.DONE, fx.step("c2").status());
        assertEquals(Duration.ofSeconds(5), fx.step("c2").elapsed());
        assertEquals(Duration.ofSeconds(6), fx.step("c1").elapsed());
        assertEquals("b", fx.step("c2").result().text());
        assertEquals(StepStatus.RUNNING, fx.step("c3").status());
        assertNull(fx.step("c3").endedAt());
    }

    @Test
    void exclusiveCallsRunOneAtATimeAndWaitingOnesAreShown() {
        TranscriptFixture fx = new TranscriptFixture(false, List.of());
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(call("c1", "write"), call("c2", "write")));

        assertEquals(StepStatus.RUNNING, fx.step("c1").status());
        assertEquals(StepStatus.WAITING, fx.step("c2").status());
        assertNull(fx.step("c2").group());
        assertNull(fx.step("c2").startedAt());

        Instant secondStart = fx.now();
        fx.feed(run, fx.finished(run, "c1", "ok", false));

        assertEquals(StepStatus.RUNNING, fx.step("c2").status());
        assertEquals(secondStart, fx.step("c2").startedAt());
        assertNotEquals(fx.step("c1").group(), fx.step("c2").group());
    }

    @Test
    void blockedByHookIsShownWithReasonAndNeverRuns() {
        TranscriptFixture fx = new TranscriptFixture(true, List.of(NO_RM));
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(call("c1", "rm"), call("c2", "read_file")));

        ToolStep blocked = fx.step("c1");
        assertEquals(StepStatus.BLOCKED, blocked.status());
        assertTrue(blocked.result().error());
        assertTrue(blocked.result().text().contains("destructive"), blocked.result().text());
        assertEquals(StepStatus.RUNNING, fx.step("c2").status());
    }

    @Test
    void invalidArgumentsAreBlockedWithTheRawText() {
        TranscriptFixture fx = new TranscriptFixture(true, List.of());
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(badCall("c1", "read_file")));

        ToolStep step = fx.step("c1");
        assertEquals(StepStatus.BLOCKED, step.status());
        assertTrue(step.result().text().contains("{oops"), step.result().text());
        assertEquals(Duration.ZERO, step.elapsed());
    }

    @Test
    void allBlockedRoundStillRecordsEveryStep() {
        TranscriptFixture fx = new TranscriptFixture(true, List.of(NO_RM));
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(call("c1", "rm"), badCall("c2", "rm")));

        assertEquals(StepStatus.BLOCKED, fx.step("c1").status());
        assertEquals(StepStatus.BLOCKED, fx.step("c2").status());
        assertEquals("AwaitingModel", fx.status(run).phase());
    }

    @Test
    void errorResultIsFailed() {
        TranscriptFixture fx = new TranscriptFixture(true, List.of());
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(call("c1", "read_file")));
        fx.feed(run, fx.finished(run, "c1", "no such file", true));

        ToolStep step = fx.step("c1");
        assertEquals(StepStatus.FAILED, step.status());
        assertNull(step.result().code());
    }

    @Test
    void cancelSynthesizesUnknownOutcomeAndAbortedResults() {
        TranscriptFixture fx = new TranscriptFixture(false, List.of());
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(call("c1", "write"), call("c2", "write")));
        fx.feed(run, new Cancel(new RequestId("x")));

        ToolStep sent = fx.step("c1");
        assertEquals(StepStatus.FAILED, sent.status());
        assertEquals(ToolPairing.OUTCOME_UNKNOWN, sent.result().code());
        assertEquals(Duration.ofSeconds(1), sent.elapsed());

        ToolStep neverSent = fx.step("c2");
        assertEquals(StepStatus.FAILED, neverSent.status());
        assertEquals(ToolPairing.ABORTED_BEFORE_DISPATCH, neverSent.result().code());
        assertNull(neverSent.elapsed());
        assertEquals(EndReason.CANCELLED, fx.status(run).endReason());
    }

    @Test
    void secondRoundOfCallsIsSeparateFromTheFirst() {
        TranscriptFixture fx = new TranscriptFixture(true, List.of());
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(call("c1", "read_file")));
        fx.feed(run, fx.finished(run, "c1", "a", false));
        fx.feed(run, fx.reply(call("c2", "grep")));
        fx.feed(run, fx.finished(run, "c2", "b", false));
        fx.feed(run, fx.reply(text("done")));

        List<ToolStep> steps = fx.items(ToolStep.class);
        assertEquals(List.of("c1", "c2"), steps.stream().map(step -> step.call().id()).toList());
        assertNotEquals(steps.get(0).group(), steps.get(1).group());
        assertTrue(steps.stream().allMatch(step -> step.status() == StepStatus.DONE));
    }
}
