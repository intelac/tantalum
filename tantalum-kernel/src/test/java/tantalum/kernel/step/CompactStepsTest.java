package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.kernel.LoopFixture.text;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.Replay;
import tantalum.kernel.compaction.SummaryPrompt;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.command.AbortCommands;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.decision.CompactionFailed;
import tantalum.value.decision.ContextCompacted;
import tantalum.value.decision.Fact;
import tantalum.value.event.Cancel;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ToolFinished;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Message;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.Compacting;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunState;
import tantalum.value.state.UsageAnchor;

/** 自动压缩和超长恢复的逐步断言。窗口 1000、预留 200：阈值 800，K = min(70, 200) = 70。 */
class CompactStepsTest {

    static final CompactionSettings SETTINGS = new CompactionSettings(1000, 200, 70, 60, 30, 500);

    /** 90 字节、30 token 的文本。 */
    static String block(String tag) {
        return (tag + " ").repeat(45).substring(0, 90);
    }

    static ModelReplied reply(LoopFixture loop, List<ContentBlock> content, StopReason stop, TokenUsage usage) {
        return new ModelReplied(loop.onlyCommandId(), content, stop, "m", usage);
    }

    static ModelReplied endTurn(LoopFixture loop, String text, long promptTotal) {
        return reply(loop, text(text), StopReason.END_TURN, new TokenUsage(promptTotal, 10, 0, 0));
    }

    static List<ContentBlock> thinkingAndCall(String id) {
        return List.of(new ReasoningBlock("claude", new Json.Str("hmm")), new TextBlock("calling"), LoopFixture.call(id, "safe_read"));
    }

    /** 跑到模型调用前：提交、组装（带设置）。 */
    static LoopFixture start(KernelHook... hooks) {
        LoopFixture loop = new LoopFixture(hooks);
        loop.prompt(block("q1"));
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), SETTINGS, List.of()));
        return loop;
    }

    /**
     * 造一段带工具轮的历史，让锚点落在阈值之上：用户 q1 → 助手（thinking + 调用）→ 结果 → 助手回复（用量 790）
     * → 用户 q2 → 组装 → 此时 S = 790 + 30 > 800，要压缩。
     */
    static LoopFixture overThreshold(KernelHook... hooks) {
        LoopFixture loop = start(hooks);
        loop.feed(loop.runId(), reply(loop, thinkingAndCall("t1"), StopReason.TOOL_USE, new TokenUsage(100, 10, 0, 0)));
        loop.feed(loop.runId(), new ToolFinished(loop.onlyCommandId(), "t1", block("r1"), false));
        loop.feed(loop.runId(), endTurn(loop, block("a1"), 790));
        assertEquals(EndReason.COMPLETED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
        assertEquals(new UsageAnchor(800, 4), loop.session().anchor());
        loop.prompt(block("q2"));
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), SETTINGS, List.of()));
        return loop;
    }

    /** 压缩后的重新组装。 */
    static void assembled(LoopFixture loop) {
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), SETTINGS, List.of()));
    }

    static CallModel onlyCall(LoopFixture loop) {
        return assertInstanceOf(CallModel.class, loop.onlyCommand());
    }

    static <T extends Fact> T fact(LoopFixture loop, Class<T> type) {
        return loop.last.facts().stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow(
                () -> new AssertionError("no " + type.getSimpleName() + " in " + loop.last.facts()));
    }

    /**
     * 超过阈值，但头部只有首条请求（摘要省不了什么），也没有可裁剪的：自动压缩放弃，同一步里继续原调用。
     * {@code beforeModelCall} 只能在这一步里跑一次，并且看到刚做完的工作。
     */
    @Test
    void autoCompactionWithTooLittleToSummarizeRunsTheModelHookOnce() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Boolean> sawWork = new java.util.concurrent.CopyOnWriteArrayList<>();
        KernelHook needsWork = new KernelHook() {
            @Override public String name() { return "needs-work"; }
            @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
                calls.incrementAndGet();
                sawWork.add(context.work() != null);
                return context.work() == null ? new NeedWork("check", new Json.Null()) : tantalum.value.hook.Allow.PLAIN;
            }
        };
        LoopFixture loop = start(needsWork);
        loop.feed(loop.runId(), new tantalum.value.event.WorkDone(loop.onlyCommandId(), "check", new Json.Null(), false, null));
        loop.feed(loop.runId(), endTurn(loop, block("a1"), 790));
        loop.prompt(block("q2"));
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), SETTINGS, List.of()));
        assertEquals("AwaitingWork", loop.phase());
        calls.set(0);
        sawWork.clear();

        loop.feed(loop.runId(), new tantalum.value.event.WorkDone(loop.onlyCommandId(), "check", new Json.Null(), false, null));

        assertEquals("AwaitingModel", loop.phase());
        assertEquals(1, calls.get(), "beforeModelCall ran more than once in one step");
        assertEquals(List.of(true), sawWork);
        assertEquals("too little to summarize", fact(loop, CompactionFailed.class).reason());
        assertEquals(1, loop.last.facts().stream().filter(CompactionFailed.class::isInstance).count());
        assertTrue(loop.run().autoCompactionFailed());
    }

    @Test
    void belowThresholdCallsTheModelDirectly() {
        LoopFixture loop = start();
        assertEquals("AwaitingModel", loop.phase());
        assertTrue(onlyCall(loop).prompt().output().toolsAllowed());
        assertNull(loop.run().compacting());
    }

    @Test
    void sessionWithoutSettingsNeverCompacts() {
        LoopFixture loop = new LoopFixture();
        loop.prompt(block("q1"));
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), null, List.of()));
        loop.feed(loop.runId(), endTurn(loop, block("a1"), 5000));
        loop.prompt(block("q2"));
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), null, List.of()));
        assertEquals("AwaitingModel", loop.phase());
        assertNull(loop.session().context().compaction());
    }

    @Test
    void overThresholdSendsASummaryCallFirst() {
        LoopFixture loop = overThreshold();
        assertEquals("AwaitingSummary", loop.phase());
        CallModel summary = onlyCall(loop);
        assertFalse(summary.prompt().output().toolsAllowed());
        assertEquals(500, summary.prompt().output().maxTokens());
        assertEquals("system", summary.prompt().system());
        Compacting compacting = loop.run().compacting();
        assertEquals(Compacting.Trigger.AUTO, compacting.trigger());
        // 单元：[q1] [thinking+call, result] [a1] [q2]；K = 70 → 留 a1 和 q2（60），头部是前两个单元（3 条消息）。
        assertEquals(3, compacting.plan().headMessages());
        assertNull(compacting.plan().request(), "q2 in the kept tail is the current request");

        List<Message> sent = summary.prompt().messages();
        assertEquals(4, sent.size(), "head of three messages plus the instruction");
        assertEquals(Message.user(block("q1")), sent.get(0));
        assertEquals(List.of(new TextBlock("calling"), LoopFixture.call("t1", "safe_read")), sent.get(1).content(),
                "thinking is stripped from the head");
        assertTrue(((TextBlock) sent.get(3).content().getFirst()).text().contains("## Goal"));
    }

    @Test
    void acceptedSummaryReplacesTheViewAndCallsTheModelWithoutRechecking() {
        LoopFixture loop = overThreshold();
        List<Message> history = loop.session().messages();
        loop.feed(loop.runId(), reply(loop, text("Goal: q1"), StopReason.END_TURN, new TokenUsage(50, 20, 0, 0)));
        ContextCompacted fact = fact(loop, ContextCompacted.class);
        assertFalse(fact.trimOnly());
        assembled(loop);

        assertEquals("AwaitingModel", loop.phase());
        CallModel call = onlyCall(loop);
        assertTrue(call.prompt().output().toolsAllowed());
        List<Message> view = call.prompt().messages();
        assertEquals(3, view.size(), "summary, kept a1, kept q2");
        assertEquals(SummaryPrompt.summaryMessage("Goal: q1"), view.get(0));
        assertEquals(Role.ASSISTANT, view.get(1).role());
        assertEquals(text(block("a1")), view.get(1).content());
        assertEquals(Message.user(block("q2")), view.get(2));
        assertEquals(history, loop.session().messages(), "history is untouched");
        assertEquals(history.size(), loop.session().compaction().tailFrom());
        assertNull(loop.session().anchor(), "the old measurement is void");
        assertNull(loop.run().compacting());

        assertEquals(Compacting.Trigger.AUTO, fact.trigger());
        assertEquals(2, fact.headUnits());
        assertEquals(2, fact.keptUnits());
        assertTrue(fact.estimateAfter() < fact.estimateBefore());
        assertEquals(new TokenUsage(50, 20, 0, 0), fact.usage());
        TokenUsage day = loop.state.principals().get(LoopFixture.USER).usageOn(java.time.LocalDate.of(2026, 10, 31));
        assertEquals(40, day.output(), "summary usage is counted: 10 + 10 + 20");
        assertEquals(940, day.promptTotal());

        // 下一次回复后锚点重建。
        loop.feed(loop.runId(), endTurn(loop, "a2", 300));
        assertEquals(new UsageAnchor(310, loop.session().messages().size()), loop.session().anchor());
        // 再追问，大小按新锚点算，不再触发。
        loop.prompt("q3");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), SETTINGS, List.of()));
        assertEquals("AwaitingModel", loop.phase());
        assertEquals(5, onlyCall(loop).prompt().messages().size(), "view = replacement(3) + a2 + q3");
    }

    @Test
    void rejectedSummaryContinuesWithoutCompactingAgainThisRun() {
        LoopFixture loop = overThreshold();
        loop.feed(loop.runId(), reply(loop, text("half a summ"), StopReason.MAX_TOKENS, null));

        assertEquals("AwaitingModel", loop.phase());
        assertNull(loop.session().compaction());
        assertTrue(loop.run().autoCompactionFailed());
        assertEquals("summary did not end normally: MAX_TOKENS", fact(loop, CompactionFailed.class).reason());
        assertEquals(5, onlyCall(loop).prompt().messages().size(), "the full history is sent");

        // 同一运行里再到模型调用前（工具轮后），仍超阈值但不再压缩。
        loop.feed(loop.runId(), reply(loop, thinkingAndCall("t2"), StopReason.TOOL_USE, new TokenUsage(900, 10, 0, 0)));
        loop.feed(loop.runId(), new ToolFinished(loop.onlyCommandId(), "t2", block("r2"), false));
        assertEquals("AwaitingModel", loop.phase());
        assertTrue(onlyCall(loop).prompt().output().toolsAllowed());

        // 下一个运行可以再试。
        loop.feed(loop.runId(), endTurn(loop, "a2", 950));
        loop.prompt("q3");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), SETTINGS, List.of()));
        assertEquals("AwaitingSummary", loop.phase());
    }

    @Test
    void summaryThatCallsAToolOrIsEmptyOrIsNotSmallerIsRejected() {
        LoopFixture loop = overThreshold();
        loop.feed(loop.runId(), reply(loop, List.of(LoopFixture.call("x", "safe_read")), StopReason.TOOL_USE, null));
        assertEquals("summary tried to call a tool", fact(loop, CompactionFailed.class).reason());

        loop = overThreshold();
        loop.feed(loop.runId(), reply(loop, text("   "), StopReason.END_TURN, null));
        assertEquals("summary is empty", fact(loop, CompactionFailed.class).reason());

        loop = overThreshold();
        loop.feed(loop.runId(), reply(loop, text("z".repeat(400)), StopReason.END_TURN, null));
        assertEquals("summary is not smaller than what it replaces", fact(loop, CompactionFailed.class).reason());
        assertEquals("AwaitingModel", loop.phase());
    }

    @Test
    void nonRetryableSummaryFailureIsNotRetried() {
        LoopFixture loop = overThreshold();
        loop.feed(loop.runId(), new ModelFailed(loop.onlyCommandId(), FailureReason.RATE_LIMITED, false, null, "", "429"));
        assertEquals("AwaitingModel", loop.phase());
        assertTrue(loop.run().autoCompactionFailed());
        assertTrue(fact(loop, CompactionFailed.class).reason().contains("429"));
        assertEquals(0, loop.run().retries(), "a summary failure does not use up model retries");
    }

    @Test
    void cancelDuringSummaryLeavesTheViewUntouched() {
        LoopFixture loop = overThreshold();
        List<Message> before = loop.session().messages();
        loop.feed(loop.runId(), new Cancel(new RequestId("c")));
        assertEquals(EndReason.CANCELLED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
        assertInstanceOf(AbortCommands.class, loop.onlyCommand());
        assertNull(loop.session().compaction());
        assertEquals(before, loop.session().messages());
    }

    @Test
    void interruptedSummaryCallDoesNotLeakPartialTextIntoHistory() {
        LoopFixture loop = overThreshold();
        int size = loop.session().messages().size();
        loop.feed(loop.runId(), new ModelInterrupted(loop.onlyCommandId(), "Goal: partial"));
        assertEquals(EndReason.CANCELLED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
        assertEquals(size, loop.session().messages().size(), "partial summary text is not a reply");
        assertNull(loop.session().compaction());
    }

    /** 压缩途中取消，摘要调用随后才以“被中断”返回：半截摘要不是回复，不进历史。 */
    @Test
    void cancelDuringSummaryThenALateInterruptLeavesHistoryAlone() {
        LoopFixture loop = overThreshold();
        int size = loop.session().messages().size();
        tantalum.value.id.CommandId summary = loop.onlyCommandId();
        RunId run = loop.runId();
        loop.feed(run, new Cancel(new RequestId("c")));
        loop.feed(run, new ModelInterrupted(summary, "Goal: partial"));
        assertEquals(EndReason.CANCELLED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
        assertEquals(size, loop.session().messages().size(), "the partial summary is not a reply");
    }

    static KernelHook refuseCompaction() {
        return new KernelHook() {
            @Override public String name() { return "refuse"; }
            @Override public HookResult beforeCompact(KernelState state, HookContext context) { return new Stop("not now"); }
        };
    }

    /** 规则 8：超长恢复被钩子拒绝时，运行以模型的原始错误失败。 */
    @Test
    void overflowRecoveryRefusedByAHookFailsWithTheOriginalError() {
        LoopFixture loop = overflowed(refuseCompaction());
        loop.feed(loop.runId(), overflow(loop));
        Ended ended = assertInstanceOf(Ended.class, loop.run().phase());
        assertEquals(EndReason.FAILED, ended.reason());
        assertEquals("prompt is too long", ended.detail());
        assertEquals("not now", fact(loop, CompactionFailed.class).reason());
    }

    /** 规则 8：手动压缩被钩子拒绝时，运行以拒绝的原因结束。 */
    @Test
    void manualCompactionRefusedByAHookEndsWithTheReason() {
        LoopFixture loop = idleAfterTwoRuns(refuseCompaction());
        loop.feed(null, compactRequest(null));
        Ended ended = assertInstanceOf(Ended.class, loop.run().phase());
        assertEquals(EndReason.FAILED, ended.reason());
        assertEquals("not now", ended.detail());
        assertNull(loop.session().activeRun());
    }

    @Test
    void beforeCompactStopIsAFailure() {
        KernelHook refuse = new KernelHook() {
            @Override public String name() { return "refuse"; }
            @Override public HookResult beforeCompact(KernelState state, HookContext context) {
                assertNotNull(context.plan());
                return new Stop("not now");
            }
        };
        LoopFixture loop = overThreshold(refuse);
        assertEquals("AwaitingModel", loop.phase());
        assertEquals("not now", fact(loop, CompactionFailed.class).reason());
        assertTrue(loop.run().autoCompactionFailed());

        KernelHook work = new KernelHook() {
            @Override public String name() { return "work"; }
            @Override public HookResult beforeCompact(KernelState state, HookContext context) { return new NeedWork("w", new Json.Null()); }
        };
        loop = overThreshold(work);
        assertEquals("AwaitingModel", loop.phase());
        assertTrue(fact(loop, CompactionFailed.class).reason().contains("work"));
    }

    static ModelFailed overflow(LoopFixture loop) {
        return new ModelFailed(loop.onlyCommandId(), FailureReason.CONTEXT_OVERFLOW, false, null, "", "prompt is too long");
    }

    /** 估算说放得下（310 + 400 < 800）而服务端报超长的情形；K = 0 只留最后一个单元，其中的工具结果会被截断。 */
    static LoopFixture overflowed(KernelHook... hooks) {
        LoopFixture loop = start(hooks);
        loop.feed(loop.runId(), reply(loop, thinkingAndCall("t1"), StopReason.TOOL_USE, new TokenUsage(100, 10, 0, 0)));
        loop.feed(loop.runId(), new ToolFinished(loop.onlyCommandId(), "t1", block("r1"), false));
        loop.feed(loop.runId(), endTurn(loop, block("a1"), 200));
        loop.prompt(block("q2"));
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), SETTINGS, List.of()));
        loop.feed(loop.runId(), reply(loop, thinkingAndCall("t2"), StopReason.TOOL_USE, new TokenUsage(300, 10, 0, 0)));
        loop.feed(loop.runId(), new ToolFinished(loop.onlyCommandId(), "t2", "y".repeat(1200), false));
        assertEquals("AwaitingModel", loop.phase(), "the estimate says it fits, so no automatic compaction");
        return loop;
    }

    @Test
    void overflowCompactsToTheLastUnitAndRetriesOnce() {
        LoopFixture loop = start();
        loop.feed(loop.runId(), reply(loop, thinkingAndCall("t1"), StopReason.TOOL_USE, new TokenUsage(100, 10, 0, 0)));
        loop.feed(loop.runId(), new ToolFinished(loop.onlyCommandId(), "t1", block("r1"), false));
        loop.feed(loop.runId(), endTurn(loop, block("a1"), 200));
        loop.prompt(block("q2"));
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), SETTINGS, List.of()));
        assertEquals("AwaitingModel", loop.phase(), "the estimate says it fits");

        loop.feed(loop.runId(), overflow(loop));
        assertEquals("AwaitingSummary", loop.phase());
        Compacting compacting = loop.run().compacting();
        assertEquals(Compacting.Trigger.OVERFLOW, compacting.trigger());
        assertEquals(new Compacting.Overflow("prompt is too long"), compacting.cause());
        assertEquals(1, compacting.plan().keptUnits(), "K = 0: only the last unit stays");
        assertEquals(4, compacting.plan().headMessages());
        assertTrue(loop.run().overflowRecovered());
        assertEquals(0, loop.run().retries(), "recovery is not a timed retry");

        loop.feed(loop.runId(), reply(loop, text("Goal: q1 then q2"), StopReason.END_TURN, null));
        ContextCompacted recovered = fact(loop, ContextCompacted.class);
        assembled(loop);
        assertEquals("AwaitingModel", loop.phase());
        CallModel retry = onlyCall(loop);
        assertTrue(retry.prompt().output().toolsAllowed());
        assertEquals(List.of(SummaryPrompt.summaryMessage("Goal: q1 then q2"), Message.user(block("q2"))),
                retry.prompt().messages(), "summary and the kept last unit q2, which is the current request");
        assertEquals(Compacting.Trigger.OVERFLOW, recovered.trigger());

        // 成功回复后标记清零，下一次超长可以再恢复一次。
        loop.feed(loop.runId(), endTurn(loop, "a2", 100));
        assertFalse(loop.run().overflowRecovered());
    }

    @Test
    void secondOverflowInTheSameCallFailsWithTheOriginalError() {
        LoopFixture loop = overflowed();
        loop.feed(loop.runId(), overflow(loop));
        assertEquals("AwaitingSummary", loop.phase());
        loop.feed(loop.runId(), reply(loop, text("Goal: q"), StopReason.END_TURN, null));
        assembled(loop);
        assertEquals("AwaitingModel", loop.phase());
        CallModel retry = onlyCall(loop);
        String kept = ((tantalum.value.model.ToolResultBlock) retry.prompt().messages().getLast().content().getFirst()).text();
        assertTrue(kept.contains("of 1200 bytes truncated"), "the oversized result is cut in the kept tail: " + kept.length());
        assertTrue(kept.length() < 200);

        loop.feed(loop.runId(), new ModelFailed(loop.onlyCommandId(), FailureReason.CONTEXT_OVERFLOW, false, null, "", "still too long"));
        Ended ended = assertInstanceOf(Ended.class, loop.run().phase());
        assertEquals(EndReason.FAILED, ended.reason());
        assertEquals("still too long", ended.detail());
    }

    @Test
    void overflowWithNothingToCompactFailsWithTheOriginalError() {
        LoopFixture loop = start();
        loop.feed(loop.runId(), overflow(loop));
        Ended ended = assertInstanceOf(Ended.class, loop.run().phase());
        assertEquals(EndReason.FAILED, ended.reason());
        assertEquals("prompt is too long", ended.detail());
        assertTrue(loop.last.facts().stream().noneMatch(CompactionFailed.class::isInstance), "no plan means no attempt");
    }

    /** 摘要被拒，但裁剪有进展：改用裁剪，运行继续（DSH：进展不依赖摘要）。 */
    @Test
    void overflowSummaryFailureFallsBackToTrimmingWhenThatHelps() {
        LoopFixture loop = overflowed();
        loop.feed(loop.runId(), overflow(loop));
        loop.feed(loop.runId(), reply(loop, text("x"), StopReason.MAX_TOKENS, null));
        assertEquals("AwaitingContext", loop.phase());
        assertTrue(fact(loop, ContextCompacted.class).trimOnly());
        assertTrue(loop.last.facts().stream().noneMatch(CompactionFailed.class::isInstance));
    }

    @Test
    void overflowSummaryFailureWithNothingToTrimReportsTheOriginalError() {
        LoopFixture loop = start();
        loop.feed(loop.runId(), endTurn(loop, block("a1"), 200));
        loop.prompt(block("q2"));
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), SETTINGS, List.of()));
        loop.feed(loop.runId(), overflow(loop));
        loop.feed(loop.runId(), reply(loop, text("x"), StopReason.MAX_TOKENS, null));
        Ended ended = assertInstanceOf(Ended.class, loop.run().phase());
        assertEquals(EndReason.FAILED, ended.reason());
        assertEquals("prompt is too long", ended.detail());
        assertEquals("summary did not end normally: MAX_TOKENS", fact(loop, CompactionFailed.class).reason());
        assertNull(loop.session().compaction());
    }

    @Test
    void sessionWithoutSettingsDoesNotRecoverFromOverflow() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("q1");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), null, List.of()));
        loop.feed(loop.runId(), endTurn(loop, "a1", 10));
        loop.prompt("q2");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), null, List.of()));
        loop.feed(loop.runId(), overflow(loop));
        assertEquals(EndReason.FAILED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
    }

    static tantalum.value.event.CompactRequested compactRequest(String instructions) {
        return new tantalum.value.event.CompactRequested(new tantalum.value.id.SubmissionId("m"), new RequestId("m"), instructions);
    }

    /** 两轮问答后空闲的会话。 */
    static LoopFixture idleAfterTwoRuns(KernelHook... hooks) {
        LoopFixture loop = start(hooks);
        loop.feed(loop.runId(), endTurn(loop, block("a1"), 100));
        loop.prompt(block("q2"));
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), SETTINGS, List.of()));
        loop.feed(loop.runId(), endTurn(loop, block("a2"), 200));
        assertNull(loop.session().activeRun());
        return loop;
    }

    @Test
    void manualCompactionIsASpecialRunThatEndsAfterTheSummaryAndReleasesQueuedPrompts() {
        LoopFixture loop = idleAfterTwoRuns();
        List<Message> history = loop.session().messages();
        loop.feed(null, compactRequest("keep the numbers"));

        assertEquals("AwaitingSummary", loop.phase());
        RunState run = loop.run();
        assertEquals(Compacting.Trigger.MANUAL, run.compacting().trigger());
        assertEquals(new Compacting.Manual("keep the numbers"), run.compacting().cause());
        assertEquals(history, loop.session().messages(), "no user message is appended");
        assertEquals(run.ref().run(), loop.session().activeRun());
        CallModel summary = onlyCall(loop);
        assertEquals("system", summary.prompt().system(), "system and tools come from the frozen session context, not a new assembly");
        assertFalse(summary.prompt().output().toolsAllowed());
        assertTrue(((TextBlock) summary.prompt().messages().getLast().content().getFirst()).text().endsWith("keep the numbers"));
        // 手动 K = 0：只留 a2，头部 q1、a1、q2。
        assertEquals(3, run.compacting().plan().headMessages());

        // 压缩期间的提交排队，压缩结束后开始。
        loop.feed(null, new tantalum.value.event.FollowUpSubmitted(new tantalum.value.id.SubmissionId("f"), new RequestId("f"), "q3"));
        assertEquals(List.of("q3"), loop.session().followUps());

        loop.feed(run.ref().run(), new ModelReplied(summary.id(), text("Goal: q"), StopReason.END_TURN, "m", new TokenUsage(30, 10, 0, 0)));
        Ended ended = assertInstanceOf(Ended.class, loop.state.runs().get(run.ref().run()).phase());
        assertEquals(EndReason.COMPLETED, ended.reason());
        assertEquals(CompactSteps.COMPACTED, ended.detail());
        assertEquals(Compacting.Trigger.MANUAL, fact(loop, ContextCompacted.class).trigger());
        assertEquals(3, loop.session().compaction().replacement().size(), "summary, request q2, kept a2");
        assertEquals("AwaitingContext", loop.phase(), "the queued follow-up started a new run");
        assertEquals(List.of(), loop.session().followUps());
    }

    @Test
    void manualCompactionWithNothingToCompactFails() {
        LoopFixture loop = start();
        loop.feed(loop.runId(), endTurn(loop, "a1", 100));
        loop.feed(null, compactRequest(null));
        Ended ended = assertInstanceOf(Ended.class, loop.run().phase());
        assertEquals(EndReason.FAILED, ended.reason());
        assertEquals(CompactSteps.NOTHING_TO_COMPACT, ended.detail());
        assertNull(loop.session().activeRun());
        assertEquals(List.of(), loop.last.commands());
    }

    @Test
    void manualCompactionIsIgnoredWhileARunIsActiveOrBeforeAnyContext() {
        LoopFixture loop = start();
        KernelState before = loop.state;
        loop.feed(null, compactRequest(null));
        assertEquals(before, loop.state, "a racing request during a run changes nothing");
        assertEquals(List.of(), loop.last.commands());

        LoopFixture fresh = new LoopFixture();
        fresh.feed(null, compactRequest(null));
        assertEquals(KernelState.empty(), fresh.state);
    }

    @Test
    void manualSummaryFailureEndsTheRunWithTheReason() {
        LoopFixture loop = idleAfterTwoRuns();
        loop.feed(null, compactRequest(null));
        RunId run = loop.runId();
        loop.feed(run, reply(loop, text("x"), StopReason.MAX_TOKENS, null));
        Ended ended = assertInstanceOf(Ended.class, loop.state.runs().get(run).phase());
        assertEquals(EndReason.FAILED, ended.reason());
        assertEquals("summary did not end normally: MAX_TOKENS", ended.detail());
        assertNull(loop.session().compaction());
        assertNull(loop.session().activeRun());
    }

    /** 需求 1.1：头部只有首条请求、保留段是超大工具结果，摘要帮不上忙，直接裁剪，运行继续。 */
    @Test
    void hugeToolResultWithASmallHeadIsTrimmedWithoutASummaryCall() {
        LoopFixture loop = start();
        loop.feed(loop.runId(), reply(loop, List.of(LoopFixture.call("t1", "safe_read")), StopReason.TOOL_USE, new TokenUsage(30, 10, 0, 0)));
        loop.feed(loop.runId(), new ToolFinished(loop.onlyCommandId(), "t1", "y".repeat(30_000), false));
        assertEquals("AwaitingContext", loop.phase(), "no summary call; the context is reassembled after trimming");
        assertNotNull(loop.session().compaction());
        assertFalse(loop.run().autoCompactionFailed());
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), SETTINGS, List.of()));
        assertEquals("AwaitingModel", loop.phase());
        String kept = ((tantalum.value.model.ToolResultBlock) onlyCall(loop).prompt().messages().getLast().content().getFirst()).text();
        assertTrue(kept.contains("of 30000 bytes truncated"), kept);
    }

    /** 需求 1.1：同样的情况走超长恢复。 */
    @Test
    void overflowFromAHugeToolResultWithASmallHeadRecovers() {
        CompactionSettings large = new CompactionSettings(100_000, 200, 70, 60, 30, 500);
        LoopFixture loop = new LoopFixture();
        loop.prompt(block("q1"));
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), large, List.of()));
        loop.feed(loop.runId(), reply(loop, List.of(LoopFixture.call("t1", "safe_read")), StopReason.TOOL_USE, new TokenUsage(30, 10, 0, 0)));
        loop.feed(loop.runId(), new ToolFinished(loop.onlyCommandId(), "t1", "y".repeat(30_000), false));
        assertEquals("AwaitingModel", loop.phase(), "the estimate fits, so no automatic compaction");
        loop.feed(loop.runId(), overflow(loop));
        assertEquals("AwaitingContext", loop.phase());
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), large, List.of()));
        assertEquals("AwaitingModel", loop.phase());
    }

    /** 需求 1.5：可重试的失败退避后用相同 prompt 重发。 */
    @Test
    void retryableSummaryFailureIsRetriedWithTheSamePrompt() {
        LoopFixture loop = overThreshold();
        CallModel first = onlyCall(loop);
        loop.feed(loop.runId(), new ModelFailed(first.id(), FailureReason.RATE_LIMITED, true, null, "", "429"));
        assertEquals("AwaitingSummaryRetry", loop.phase());
        loop.feed(loop.runId(), new tantalum.value.event.TimerFired(loop.onlyCommandId()));
        assertEquals("AwaitingSummary", loop.phase());
        assertEquals(first.prompt(), onlyCall(loop).prompt());
        assertTrue(loop.last.facts().stream().noneMatch(CompactionFailed.class::isInstance));
    }

    @Test
    void summaryFailureAfterTheLastRetryFallsBackToTheRule() {
        LoopFixture loop = overThreshold();
        for (int i = 0; i < RetrySteps.MAX_RETRIES; i++) {
            loop.feed(loop.runId(), new ModelFailed(loop.onlyCommandId(), FailureReason.RATE_LIMITED, true, null, "", "429"));
            loop.feed(loop.runId(), new tantalum.value.event.TimerFired(loop.onlyCommandId()));
        }
        loop.feed(loop.runId(), new ModelFailed(loop.onlyCommandId(), FailureReason.RATE_LIMITED, true, null, "", "429"));
        assertEquals("AwaitingModel", loop.phase());
        assertTrue(loop.run().autoCompactionFailed());
        assertEquals(0, loop.run().retries());
    }

    @Test
    void cancelWhileWaitingToRetryTheSummary() {
        LoopFixture loop = overThreshold();
        loop.feed(loop.runId(), new ModelFailed(loop.onlyCommandId(), FailureReason.RATE_LIMITED, true, null, "", "429"));
        loop.feed(loop.runId(), new Cancel(new RequestId("c")));
        assertEquals(EndReason.CANCELLED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
        assertNull(loop.session().compaction());
    }

    /** 需求 1.3：system 太大、头部一条都放不下时不发摘要调用，以原始错误结束。 */
    @Test
    void nothingFitsInASummaryCallSoNoneIsSent() {
        LoopFixture loop = new LoopFixture();
        loop.prompt(block("q1"));
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s".repeat(3000), List.of(), SETTINGS, List.of()));
        loop.feed(loop.runId(), endTurn(loop, block("a1"), 10));
        loop.prompt(block("q2"));
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s".repeat(3000), List.of(), SETTINGS, List.of()));
        loop.feed(loop.runId(), overflow(loop));
        assertTrue(loop.last.commands().stream().noneMatch(CallModel.class::isInstance));
        Ended ended = assertInstanceOf(Ended.class, loop.run().phase());
        assertEquals("prompt is too long", ended.detail());
    }

    /** 需求 1.5：重试过的摘要调用回放后 prompt 仍然相同。 */
    @Test
    void replayOfARetriedSummaryCallMatches() {
        LoopFixture loop = overThreshold();
        loop.feed(loop.runId(), new ModelFailed(loop.onlyCommandId(), FailureReason.RATE_LIMITED, true, null, "", "429"));
        loop.feed(loop.runId(), new tantalum.value.event.TimerFired(loop.onlyCommandId()));
        loop.feed(loop.runId(), reply(loop, text("Goal: q1"), StopReason.END_TURN, new TokenUsage(50, 20, 0, 0)));
        Replay.Result replayed = Replay.run(loop.kernel, loop.journal);
        assertEquals(loop.commands, replayed.commands());
        assertEquals(loop.state, replayed.state());
    }

    /** 设计补 1.9（规则 5）：压缩后重新组装，新的 system、工具和记忆生效，不追加更新消息，也不连续压缩。 */
    @Test
    void contextIsReassembledAfterCompaction() {
        LoopFixture loop = overThreshold();
        loop.feed(loop.runId(), reply(loop, text("Goal: q1"), StopReason.END_TURN, new TokenUsage(50, 20, 0, 0)));
        assertEquals("AwaitingContext", loop.phase());
        int size = loop.session().view().size();
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "new system", List.of(), SETTINGS,
                List.of(new tantalum.value.state.ContextSection("memory-index", "new index", true))));
        assertEquals("AwaitingModel", loop.phase());
        CallModel call = onlyCall(loop);
        assertEquals("new system", call.prompt().system());
        assertEquals(size, call.prompt().messages().size(), "the new index is in the system, no update message");
        assertEquals("new index", loop.session().context().sections().getFirst().body());
    }

    @Test
    void replayRecomputesTheSamePromptsFromTheJournal() {
        LoopFixture loop = overThreshold();
        loop.feed(loop.runId(), reply(loop, text("Goal: q1"), StopReason.END_TURN, new TokenUsage(50, 20, 0, 0)));
        assembled(loop);
        loop.feed(loop.runId(), endTurn(loop, "a2", 300));
        List<Command> live = List.copyOf(loop.commands);

        Replay.Result replayed = Replay.run(loop.kernel, loop.journal);
        assertEquals(live, replayed.commands(), "every CallModel, including the summary call, is recomputed exactly");
        assertEquals(loop.state, replayed.state());
    }

    /** 记下每次 {@code beforeCompact} 看到的触发方式，模型调用前也记一次（那里应当没有触发方式）。测试专用，不是纯函数。 */
    static final class TriggerRecorder implements KernelHook {
        final List<Compacting.Trigger> compacts = new java.util.ArrayList<>();
        final List<Compacting.Trigger> modelCalls = new java.util.ArrayList<>();

        @Override public String name() { return "recorder"; }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
            modelCalls.add(context.trigger());
            return tantalum.value.hook.Allow.PLAIN;
        }
        @Override public HookResult beforeCompact(KernelState state, HookContext context) {
            assertNotNull(context.plan());
            compacts.add(context.trigger());
            return tantalum.value.hook.Allow.PLAIN;
        }
    }

    /** 回放会重跑钩子：回放时看到的触发方式和实时的一样，状态也一样。 */
    static void assertReplaySeesTheSame(LoopFixture loop, TriggerRecorder recorder) {
        List<Compacting.Trigger> live = List.copyOf(recorder.compacts);
        assertEquals(loop.state, Replay.run(loop.kernel, loop.journal).state());
        assertEquals(live, recorder.compacts.subList(live.size(), recorder.compacts.size()));
    }

    /** 需求 3.1、3.3：自动、超长恢复、手动三种触发方式都传给 {@code beforeCompact}；其他位置没有触发方式。 */
    @Test
    void beforeCompactSeesHowCompactionWasTriggered() {
        TriggerRecorder auto = new TriggerRecorder();
        LoopFixture loop = overThreshold(auto);
        assertEquals("AwaitingSummary", loop.phase());
        assertEquals(List.of(Compacting.Trigger.AUTO), auto.compacts);
        assertTrue(auto.modelCalls.stream().allMatch(java.util.Objects::isNull), auto.modelCalls.toString());
        assertReplaySeesTheSame(loop, auto);

        TriggerRecorder overflow = new TriggerRecorder();
        loop = overflowed(overflow);
        loop.feed(loop.runId(), overflow(loop));
        assertEquals("AwaitingSummary", loop.phase());
        assertEquals(List.of(Compacting.Trigger.OVERFLOW), overflow.compacts);
        assertReplaySeesTheSame(loop, overflow);

        TriggerRecorder manual = new TriggerRecorder();
        loop = idleAfterTwoRuns(manual);
        loop.feed(null, compactRequest(null));
        assertEquals("AwaitingSummary", loop.phase());
        assertEquals(List.of(Compacting.Trigger.MANUAL), manual.compacts);
        assertReplaySeesTheSame(loop, manual);
    }

    /** 需求 3.2：摘要重试用同一个计划重发，不再问 {@code beforeCompact}。 */
    @Test
    void summaryRetryDoesNotAskBeforeCompactAgain() {
        TriggerRecorder recorder = new TriggerRecorder();
        LoopFixture loop = overThreshold(recorder);
        assertEquals(List.of(Compacting.Trigger.AUTO), recorder.compacts);
        loop.feed(loop.runId(), new ModelFailed(loop.onlyCommandId(), FailureReason.RATE_LIMITED, true, null, "", "429"));
        loop.feed(loop.runId(), new tantalum.value.event.TimerFired(loop.onlyCommandId()));
        assertEquals("AwaitingSummary", loop.phase(), "the summary was sent again");
        assertEquals(List.of(Compacting.Trigger.AUTO), recorder.compacts, "the retry does not ask beforeCompact");
        assertReplaySeesTheSame(loop, recorder);
    }
}
