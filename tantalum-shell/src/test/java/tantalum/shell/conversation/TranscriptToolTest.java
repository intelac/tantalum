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

    private static tantalum.value.question.Question confirm(String id) {
        return new tantalum.value.question.Question(id, tantalum.value.text.LocalText.of("Go on?"), null, List.of(
                new tantalum.value.question.Question.Choice("once", tantalum.value.text.LocalText.of("Allow once")),
                new tantalum.value.question.Question.Choice("deny", tantalum.value.text.LocalText.of("Deny"))));
    }

    /** 调 write_file 前问；{@code beforeModel} 时在第一次模型调用前也问一次。 */
    private static KernelHook asking(boolean beforeModel) {
        return new KernelHook() {
            @Override
            public String name() {
                return "asking";
            }

            @Override
            public HookResult beforeModelCall(KernelState state, HookContext context) {
                boolean asked = state.sessions().get(context.run().session()).note("asking").fields().containsKey("asked");
                return beforeModel && !asked ? new tantalum.value.hook.Ask(confirm("before-model")) : tantalum.value.hook.Allow.PLAIN;
            }

            @Override
            public HookResult beforeToolCall(KernelState state, HookContext context) {
                return context.call().name().equals("write_file")
                        ? new tantalum.value.hook.Ask(confirm("approval-" + context.call().id())) : tantalum.value.hook.Allow.PLAIN;
            }

            @Override
            public HookResult onAnswer(KernelState state, HookContext context) {
                return tantalum.value.hook.Allow.PLAIN.withNote("asking",
                        new tantalum.value.json.Json.Obj(java.util.Map.of("asked", new tantalum.value.json.Json.Bool(true))));
            }
        };
    }

    /** input-area 需求 2.1：规则插件在工具调用前问的问题带上被问的那次调用，回答之后还带着。 */
    @Test
    void questionAskedBeforeAToolCallNamesThatCall() {
        TranscriptFixture fx = new TranscriptFixture(true, List.of(asking(false)));
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(call("c1", "read_file"), call("c2", "write_file"), call("c3", "write_file")));

        TranscriptItem.Question first = fx.items(TranscriptItem.Question.class).getFirst();
        assertEquals("approval-c2", first.questionId());
        assertEquals("c2", first.toolCallId());
        assertEquals("c2", TranscriptItem.Question.askedAbout(fx.state().runs().get(run)));

        fx.feed(run, fx.answered("approval-c2", "once"));
        List<TranscriptItem.Question> questions = fx.items(TranscriptItem.Question.class);
        assertEquals(2, questions.size());
        assertEquals(TranscriptItem.QuestionStatus.ANSWERED, questions.get(0).status());
        assertEquals("c2", questions.get(0).toolCallId(), "kept after the answer");
        assertEquals("c3", questions.get(1).toolCallId());
    }

    @Test
    void questionsFromOtherMomentsNameNoCall() {
        TranscriptFixture fx = new TranscriptFixture(true, List.of(asking(true)));
        RunId run = fx.start("hi");
        TranscriptItem.Question beforeModel = fx.items(TranscriptItem.Question.class).getFirst();
        assertEquals("before-model", beforeModel.questionId());
        assertNull(beforeModel.toolCallId());
        assertNull(TranscriptItem.Question.askedAbout(fx.state().runs().get(run)));

        fx.feed(run, fx.answered("before-model", "once"));
        tantalum.value.json.Json arguments = new tantalum.value.json.Json.Obj(java.util.Map.of("question", new tantalum.value.json.Json.Str("Which?")));
        fx.feed(run, fx.reply(new tantalum.value.model.ToolCallBlock("a1", "ask_user", "{\"question\":\"Which?\"}", arguments)));
        TranscriptItem.Question fromModel = fx.items(TranscriptItem.Question.class).getLast();
        assertEquals("ask_user", fromModel.askedBy());
        assertNull(fromModel.toolCallId(), "ask_user questions are answered in the conversation, not on the card");
    }

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
