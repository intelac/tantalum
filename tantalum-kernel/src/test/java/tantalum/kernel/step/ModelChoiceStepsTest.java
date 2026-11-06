package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.kernel.LoopFixture.text;
import static tantalum.kernel.step.CompactStepsTest.block;
import static tantalum.kernel.step.CompactStepsTest.reply;
import static tantalum.kernel.step.CompactStepsTest.thinkingAndCall;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.event.CompactRequested;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChangeBlock;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.OwnerRef;

/**
 * model-picker 设计“一次提问怎么选模型”第 3、5、6 步和“换到窗口更小的模型”：运行开始时把提交里的选择交给 shell，shell 定下的
 * 记进会话和运行；路线或模型变了追加一条说明（只追加）；换到窗口更小的模型时压缩前的摘要用上一个模型写，没压缩成就退回。
 */
class ModelChoiceStepsTest {

    private static final ModelChoice SONNET = new ModelChoice("claude", "claude-sonnet-5-5", "medium");
    private static final ModelChoice SONNET_HIGH = new ModelChoice("claude", "claude-sonnet-5-5", "high");
    private static final ModelChoice QWEN = new ModelChoice("local", "qwen3.8", "on");

    /** 大窗口：压缩点 5000，怎么都不压缩。 */
    private static final CompactionSettings BIG = new CompactionSettings(10000, 200, 70, 60, 30, 500, 5000, 3);
    /** 小窗口：压缩点 800。 */
    private static final CompactionSettings SMALL = new CompactionSettings(1000, 200, 70, 60, 30, 500, 800, 3);

    private static UserPrompt prompt(LoopFixture loop, String text, ModelChoice model) {
        return new UserPrompt(new SubmissionId("sub-" + text), new RequestId("req-" + text), new OwnerRef.OfSession(LoopFixture.SESSION),
                text, List.of(), model);
    }

    /** shell 组装上下文：这次用 {@code model}。 */
    private static void assembled(LoopFixture loop, ModelChoice model, CompactionSettings settings) {
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), settings, List.of(), null, null,
                Map.of(), model));
    }

    /** 一次完整的提问：提交带 {@code requested}，shell 定下 {@code used}，模型直接回答。 */
    private static void ask(LoopFixture loop, String text, ModelChoice requested, ModelChoice used) {
        loop.feed(null, prompt(loop, text, requested));
        assembled(loop, used, BIG);
        loop.feed(loop.runId(), CompactStepsTest.endTurn(loop, "a-" + text, 100));
    }

    private static CallModel call(LoopFixture loop) {
        return assertInstanceOf(CallModel.class, loop.onlyCommand());
    }

    private static Message change(ModelChoice from, ModelChoice to) {
        return new Message(Role.SYSTEM, List.of(new ModelChangeBlock(from.label(), to.label())), false);
    }

    @Test
    void theSubmittedChoiceGoesToTheShellWithTheSessionsCurrentOne() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", SONNET, SONNET);
        loop.feed(null, prompt(loop, "q2", QWEN));
        AssembleContext assemble = assertInstanceOf(AssembleContext.class, loop.onlyCommand());
        assertEquals(QWEN, assemble.requested());
        assertEquals(SONNET, assemble.current());
    }

    /** 需求 5.1：这次运行的调用用 shell 定下的模型；它记成会话和运行的模型。 */
    @Test
    void everyCallOfTheRunUsesTheChosenModel() {
        LoopFixture loop = new LoopFixture();
        loop.feed(null, prompt(loop, "q1", QWEN));
        assembled(loop, QWEN, BIG);
        assertEquals(QWEN, call(loop).prompt().model());
        loop.feed(loop.runId(), reply(loop, thinkingAndCall("t1"), StopReason.TOOL_USE, new TokenUsage(100, 10, 0, 0)));
        loop.feed(loop.runId(), new ToolFinished(loop.onlyCommandId(), "t1", block("r1"), false, null));
        assertEquals(QWEN, call(loop).prompt().model(), "the tool round keeps the model");
        assertEquals(QWEN, loop.session().model());
        assertEquals(QWEN, loop.run().model());
    }

    /** 需求 5.3：路线或模型变了，这次提问后面追加一条说明，之前的消息一个不动。 */
    @Test
    void switchingTheModelAppendsANoticeAfterTheQuestion() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", SONNET, SONNET);
        List<Message> before = loop.session().messages();
        loop.feed(null, prompt(loop, "q2", QWEN));
        assembled(loop, QWEN, BIG);

        List<Message> after = loop.session().messages();
        assertEquals(before, after.subList(0, before.size()), "what was sent stays as it was");
        assertEquals(List.of(Message.user("q2"), change(SONNET, QWEN)), after.subList(before.size(), after.size()));
        CallModel call = call(loop);
        assertEquals(QWEN, call.prompt().model());
        assertEquals(change(SONNET, QWEN), call.prompt().messages().getLast());
        assertEquals(SONNET, loop.run().modelBefore(), "kept for a compaction before the first call");
    }

    @Test
    void onlyChangingTheLevelTellsTheModelNothing() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", SONNET, SONNET);
        int size = loop.session().messages().size();
        loop.feed(null, prompt(loop, "q2", SONNET_HIGH));
        assembled(loop, SONNET_HIGH, BIG);
        assertEquals(List.of(Message.user("q2")), loop.session().messages().subList(size, loop.session().messages().size()));
        assertEquals(SONNET_HIGH, call(loop).prompt().model());
        assertEquals(SONNET_HIGH, loop.session().model());
        assertNull(loop.run().modelBefore());
    }

    /** 需求 7.4：本 spec 之前的会话没记过模型，第一次记下的选择不算换模型。 */
    @Test
    void theFirstChoiceOfAnOldSessionIsNotASwitch() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("q1");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), null, List.of()));
        assertNull(call(loop).prompt().model(), "old events carry no choice");
        loop.feed(loop.runId(), CompactStepsTest.endTurn(loop, "a1", 100));
        int size = loop.session().messages().size();
        loop.feed(null, prompt(loop, "q2", null));
        assembled(loop, QWEN, BIG);
        assertEquals(List.of(Message.user("q2")), loop.session().messages().subList(size, loop.session().messages().size()));
        assertEquals(QWEN, loop.session().model());
    }

    /** 需求 5.2：运行中的插话并进当前运行，不带选择，不换模型。 */
    @Test
    void aSteerKeepsTheRunsModel() {
        LoopFixture loop = new LoopFixture();
        loop.feed(null, prompt(loop, "q1", SONNET));
        assembled(loop, SONNET, BIG);
        loop.feed(loop.runId(), reply(loop, thinkingAndCall("t1"), StopReason.TOOL_USE, new TokenUsage(100, 10, 0, 0)));
        var tool = loop.onlyCommandId();
        loop.feed(loop.runId(), new SteerSubmitted(new SubmissionId("s1"), new RequestId("rs1"), "also this"));
        loop.feed(loop.runId(), new ToolFinished(tool, "t1", block("r1"), false, null));
        CallModel call = call(loop);
        assertEquals(SONNET, call.prompt().model());
        assertEquals(Message.user("also this"), call.prompt().messages().getLast());
    }

    /** 需求 5.2：运行中排队的提问带的新选择不影响正在跑的运行，排队的那个开始时用它。 */
    @Test
    void aQueuedFollowUpStartsWithItsOwnChoice() {
        LoopFixture loop = new LoopFixture();
        loop.feed(null, prompt(loop, "q1", SONNET));
        assembled(loop, SONNET, BIG);
        loop.feed(loop.runId(), reply(loop, thinkingAndCall("t1"), StopReason.TOOL_USE, new TokenUsage(100, 10, 0, 0)));
        var tool = loop.onlyCommandId();
        loop.feed(null, new FollowUpSubmitted(new SubmissionId("f1"), new RequestId("rf1"), "next", List.of(), QWEN));
        assertEquals(QWEN, loop.session().followUpModel());
        loop.feed(loop.runId(), new ToolFinished(tool, "t1", block("r1"), false, null));
        assertEquals(SONNET, call(loop).prompt().model(), "the running run is not affected");

        loop.feed(loop.runId(), CompactStepsTest.endTurn(loop, "a1", 100));
        AssembleContext assemble = assertInstanceOf(AssembleContext.class, loop.onlyCommand());
        assertEquals(QWEN, assemble.requested());
        assertEquals(SONNET, assemble.current());
        assertNull(loop.session().followUpModel(), "handed to the run that took it");
    }

    /** 手动压缩不组装上下文，摘要用会话现在的模型。 */
    @Test
    void aManualCompactionUsesTheSessionsModel() {
        LoopFixture loop = history(QWEN, SMALL);
        loop.feed(null, new CompactRequested(new SubmissionId("c1"), new RequestId("rc1"), null));
        assertEquals(QWEN, call(loop).prompt().model());
    }

    /**
     * 第一次 Sonnet（大窗口）留下 S = 800 的历史，再换到 qwen（压缩点 800）：第一次调 qwen 之前要压缩。
     */
    private static LoopFixture switchToSmallWindow() {
        LoopFixture loop = history(SONNET, BIG);
        loop.feed(null, prompt(loop, block("q2"), QWEN));
        assembled(loop, QWEN, SMALL);
        return loop;
    }

    /** 一轮带工具的问答，最后一次回复实测 800：在压缩点 800 的模型上，下一次提问要先压缩，头部够摘要。 */
    private static LoopFixture history(ModelChoice model, CompactionSettings settings) {
        LoopFixture loop = new LoopFixture();
        loop.feed(null, prompt(loop, "q1", model));
        assembled(loop, model, settings);
        loop.feed(loop.runId(), reply(loop, thinkingAndCall("t1"), StopReason.TOOL_USE, new TokenUsage(100, 10, 0, 0)));
        loop.feed(loop.runId(), new ToolFinished(loop.onlyCommandId(), "t1", block("r1") + block("r2"), false, null));
        loop.feed(loop.runId(), CompactStepsTest.endTurn(loop, block("a1"), 790));
        return loop;
    }

    /** 需求 5.4：摘要用上一个模型写，压缩后的调用用新模型，新模型回复后不再记着上一个。 */
    @Test
    void theCompactionBeforeASwitchIsWrittenByThePreviousModel() {
        LoopFixture loop = switchToSmallWindow();
        assertEquals("AwaitingSummary", loop.phase());
        assertEquals(SONNET, call(loop).prompt().model(), "Sonnet still fits the history, qwen may not");

        loop.feed(loop.runId(), reply(loop, text("Goal: sum"), StopReason.END_TURN, null));
        AssembleContext again = assertInstanceOf(AssembleContext.class, loop.onlyCommand());
        assertEquals(QWEN, again.requested(), "reassembling keeps the run's model");
        assembled(loop, QWEN, SMALL);
        assertEquals(QWEN, call(loop).prompt().model());
        assertEquals(SONNET, loop.run().modelBefore());

        loop.feed(loop.runId(), CompactStepsTest.endTurn(loop, "a2", 300));
        assertNull(loop.run().modelBefore());
        assertEquals(QWEN, loop.session().model());
    }

    /** 需求 5.5：摘要没用上，运行失败并写明换到哪个，会话的模型退回上一个，再追加一条反方向的说明。 */
    @Test
    void aFailedCompactionBeforeASwitchFailsTheRunAndGoesBack() {
        LoopFixture loop = switchToSmallWindow();
        List<Message> before = loop.session().messages();
        loop.feed(loop.runId(), new ModelFailed(loop.onlyCommandId(), FailureReason.RATE_LIMITED, false, null, "", "429", null, null, null));

        Ended ended = assertInstanceOf(Ended.class, loop.run().phase());
        assertEquals(EndReason.FAILED, ended.reason());
        assertEquals(RunEnd.SWITCH_COMPACTION_FAILED + QWEN.label(), ended.detail());
        assertEquals(SONNET, loop.session().model());
        List<Message> after = loop.session().messages();
        assertEquals(before, after.subList(0, before.size()), "the first notice is not taken back");
        assertEquals(List.of(change(QWEN, SONNET)), after.subList(before.size(), after.size()));
        assertTrue(loop.last.commands().stream().noneMatch(CallModel.class::isInstance), "qwen is never called");

        // 下一次提问没带选择：用会话现在的 Sonnet，不再追加说明
        int size = after.size();
        loop.feed(null, prompt(loop, "q3", null));
        assertEquals(SONNET, assertInstanceOf(AssembleContext.class, loop.onlyCommand()).current());
        assembled(loop, SONNET, BIG);
        assertEquals(List.of(Message.user("q3")), loop.session().messages().subList(size, loop.session().messages().size()));
    }

    /** 不换模型时，摘要没用上照旧继续原调用（context-compaction 规则 8）。 */
    @Test
    void withoutASwitchAFailedCompactionStillProceeds() {
        LoopFixture loop = history(QWEN, SMALL);
        loop.feed(null, prompt(loop, block("q2"), QWEN));
        assembled(loop, QWEN, SMALL);
        assertEquals("AwaitingSummary", loop.phase());
        loop.feed(loop.runId(), new ModelFailed(loop.onlyCommandId(), FailureReason.RATE_LIMITED, false, null, "", "429", null, null, null));
        assertEquals("AwaitingModel", loop.phase());
        assertEquals(QWEN, call(loop).prompt().model());
    }

    /** M11：一开始就有关着的工具，或者历史里有工具变化，算“有工具变化”。 */
    @Test
    void toolChangesInHistory() {
        List<ToolSpec> all = List.of(new ToolSpec("grep", "grep", new Json.Obj(Map.of())), new ToolSpec("bash", "bash", new Json.Obj(Map.of())));
        LoopFixture allOn = new LoopFixture();
        allOn.prompt("q1");
        allOn.feed(allOn.runId(), new ContextAssembled(allOn.onlyCommandId(), "s", all, null, List.of(), List.of("grep", "bash"), null));
        assertFalse(allOn.session().toolChangesInHistory());

        LoopFixture oneOff = new LoopFixture();
        oneOff.prompt("q1");
        oneOff.feed(oneOff.runId(), new ContextAssembled(oneOff.onlyCommandId(), "s", all, null, List.of(), List.of("grep"), null));
        assertTrue(oneOff.session().toolChangesInHistory(), "bash was off from the start");

        allOn.feed(allOn.runId(), CompactStepsTest.endTurn(allOn, "a1", 100));
        allOn.prompt("q2");
        allOn.feed(allOn.runId(), new ContextAssembled(allOn.onlyCommandId(), "s", all, null, List.of(), List.of("grep"), null));
        assertTrue(allOn.session().toolChangesInHistory(), "bash was turned off mid-session");
    }
}
