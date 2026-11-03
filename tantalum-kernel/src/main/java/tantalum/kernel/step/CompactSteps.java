package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tantalum.kernel.compaction.Compactor;
import tantalum.kernel.compaction.SummaryPrompt;
import tantalum.kernel.compaction.TokenEstimate;
import tantalum.kernel.hook.HookOutcome;
import tantalum.kernel.projection.PromptProjection;
import tantalum.kernel.table.StepContext;
import tantalum.value.decision.CompactionFailed;
import tantalum.value.decision.ContextCompacted;
import tantalum.value.decision.Decision;
import tantalum.value.event.CompactRequested;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.PromptPatch;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.Compacting;
import tantalum.value.state.Compacting.Cause;
import tantalum.value.state.Compaction;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.EndReason;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.Plan;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** 上下文压缩的处理函数：发摘要调用、收摘要、失败处理。规则见 context-compaction 需求。 */
public final class CompactSteps {

    public static final String AWAITING_SUMMARY = "AwaitingSummary";
    public static final String AWAITING_SUMMARY_RETRY = "AwaitingSummaryRetry";
    public static final String COMPACTED = "compacted";
    public static final String NOTHING_TO_COMPACT = "nothing to compact";
    private static final String NOTHING_FITS = "nothing fits in a summary call";
    private static final String TOO_LITTLE = "too little to summarize";

    private CompactSteps() {}

    /** 调模型前看要不要压缩的结果。 */
    enum Start {
        /** 没有接管：调用方取 {@code out} 里这个运行的当前状态，接着做原来的事。 */
        PROCEED,
        /** 压缩接管了这一步：发了摘要调用、改用了裁剪，或者结束了运行。 */
        TOOK_OVER
    }

    /** 规则 8 里摘要走到了哪一步：被钩子拒绝、没有发出（内容太少或放不下）、发出后没有采用。 */
    private enum Stage {
        REFUSED,
        NOT_SENT,
        NOT_ADOPTED
    }

    /** 调模型前的压缩判断。自动压缩在本运行已经失败过就不再尝试；没有可压缩的计划时不接管。 */
    static Start tryStart(Outcome out, RunState run, PromptPatch patch, Cause cause) {
        if (cause instanceof Compacting.Auto && run.autoCompactionFailed()) {
            return Start.PROCEED;
        }
        SessionState session = out.session(run.ref());
        Prompt base = PromptProjection.project(session, patch);
        Optional<Plan> planned = Compactor.plan(session, base.system(), base.tools(), cause.trigger());
        if (planned.isEmpty()) {
            return Start.PROCEED;
        }
        Compacting compacting = new Compacting(cause, planned.orElseThrow(), patch);

        HookOutcome hook = out.hooks.run(HookPoint.BEFORE_COMPACT, out.state(), out.hookContext(run.ref())
                .withCompaction(compacting.plan(), compacting.trigger()));
        out.report(hook.facts());
        if (!(hook.result() instanceof Allow)) {
            String reason = hook.result() instanceof Stop stop ? stop.reason() : "beforeCompact asked for work, which is not supported";
            return notAdopted(out, run, compacting, reason, Stage.REFUSED);
        }
        if (!Compactor.summaryCanHelp(compacting.plan(), session.context().compaction())) {
            return notAdopted(out, run, compacting, cause instanceof Compacting.Manual ? NOTHING_TO_COMPACT : TOO_LITTLE, Stage.NOT_SENT);
        }
        if (!sendSummary(out, run, compacting)) {
            return notAdopted(out, run, compacting, NOTHING_FITS, Stage.NOT_SENT);
        }
        return Start.TOOK_OVER;
    }

    /** 发摘要调用，返回是否发出。重试时用同一个计划，prompt 相同。除旧摘要外什么都放不下时不发。 */
    private static boolean sendSummary(Outcome out, RunState run, Compacting compacting) {
        SessionState session = out.session(run.ref());
        CompactionSettings settings = session.context().compaction();
        Prompt base = PromptProjection.project(session, compacting.patch());
        String instructions = compacting.cause() instanceof Compacting.Manual manual ? manual.instructions() : null;
        Message instruction = SummaryPrompt.instruction(false, instructions);
        long fixed = TokenEstimate.ofText(base.system()) + TokenEstimate.ofTools(base.tools()) + TokenEstimate.ofMessage(instruction);
        Compactor.Head head = Compactor.headForSummary(compacting.plan(), session.view(), settings, fixed);
        if (head.nothingToSummarize()) {
            return false;
        }
        List<Message> messages = new ArrayList<>(head.messages());
        messages.add(SummaryPrompt.instruction(head.dropped(), instructions));
        Prompt prompt = new Prompt(base.system(), base.tools(), messages, new Prompt.Output(false, settings.summaryMaxTokens()));

        CommandId id = ModelSteps.emitCall(out, run.ref(), prompt);
        out.await(run.withCompacting(compacting), AWAITING_SUMMARY, Set.of(id));
        return true;
    }

    /**
     * 手动压缩：空闲会话上开一个特殊运行，不追加用户消息，不组装上下文（用会话冻结的 system 和工具）。
     * 会话正在运行、还没有上下文或视图为空时忽略；提交方通常已先拦下这些情况，只有竞争才会走到这里。
     */
    public static Decision requested(StepContext context) {
        CompactRequested event = (CompactRequested) context.envelope().event();
        EventEnvelope envelope = context.envelope();
        SessionState session = context.state().sessions().get(envelope.session());
        if (session == null || session.activeRun() != null || session.context() == null || session.view().isEmpty()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        RunState run = SubmitSteps.newRun(out, session, new OwnerRef.OfSession(envelope.session()));
        if (tryStart(out, run, PromptPatch.NONE, new Compacting.Manual(event.instructions())) == Start.PROCEED) {
            RunEnd.finish(out, run, EndReason.FAILED, NOTHING_TO_COMPACT);
        }
        return out.decision();
    }

    public static Decision summaryReplied(StepContext context) {
        ModelReplied event = (ModelReplied) context.envelope().event();
        Outcome out = new Outcome(context);
        RunState run = context.run();
        Compacting compacting = run.compacting();
        out.addUsage(run.ref(), event.usage());

        Plan plan = compacting.plan();
        long request = plan.request() == null ? 0 : TokenEstimate.ofMessage(plan.request());
        String reason = SummaryPrompt.validate(event.content(), event.stop(), plan.headEstimate(), request);
        if (reason != null) {
            notAdopted(out, run, compacting, reason, Stage.NOT_ADOPTED);
            return out.decision();
        }

        SessionState session = out.session(run.ref());
        List<Message> view = session.view();
        List<Message> replacement = Compactor.replacement(plan, view, SummaryPrompt.text(event.content()), session.context().compaction());
        adopt(out, run, compacting, replacement, plan.headUnits(), plan.keptUnits(), event.usage(), false);
        return out.decision();
    }

    /** 换视图，然后接着走：手动压缩到此结束；其余重新组装 system 和工具（规则 5），之后的那次调用不再判断（规则 3.1）。 */
    private static void adopt(Outcome out, RunState run, Compacting compacting, List<Message> replacement, int headUnits, int keptUnits,
                              TokenUsage usage, boolean trimOnly) {
        SessionState session = out.session(run.ref());
        long before = TokenEstimate.ofMessages(session.view());
        out.put(session.compacted(new Compaction(replacement, session.messages().size())).withContext(null));
        out.report(List.of(new ContextCompacted(run.ref(), compacting.trigger(), headUnits, keptUnits, before,
                TokenEstimate.ofMessages(replacement), usage, trimOnly)));
        RunState resumed = run.withCompacting(null).withRetries(0);
        out.put(resumed);
        if (compacting.cause() instanceof Compacting.Manual) {
            RunEnd.finish(out, resumed, EndReason.COMPLETED, COMPACTED);
        } else {
            ContextSteps.reassemble(out, resumed);
        }
    }

    /** 可重试的失败退避后重发（pi 的做法），不改变视图；用完才按规则 8 处理。 */
    public static Decision summaryFailed(StepContext context) {
        ModelFailed event = (ModelFailed) context.envelope().event();
        Outcome out = new Outcome(context);
        RunState run = context.run();
        if (!event.retryable() || !RetrySteps.scheduleRetry(out, run, event.retryAfter(), AWAITING_SUMMARY_RETRY)) {
            notAdopted(out, run, run.compacting(), "summary call failed: " + event.reason() + " " + event.detail(), Stage.NOT_ADOPTED);
        }
        return out.decision();
    }

    public static Decision summaryRetry(StepContext context) {
        Outcome out = new Outcome(context);
        // 重试时摘要已经发过一次，计划没变，放不下的情况按“没有采用”处理。
        if (!sendSummary(out, context.run(), context.run().compacting())) {
            notAdopted(out, context.run(), context.run().compacting(), NOTHING_FITS, Stage.NOT_ADOPTED);
        }
        return out.decision();
    }

    /**
     * 规则 8：摘要没有用上。先看能否改用裁剪（DSH：进展不依赖摘要）——钩子拒绝时不裁剪，手动压缩只在摘要没发出时裁剪。
     * 不能裁剪时报告失败，再按触发方式：自动 → 本运行不再自动压缩，继续原调用（摘要发出过就在这里调，否则交回调用方）；
     * 超长恢复 → 以原始错误失败；手动 → 以原因失败。
     */
    private static Start notAdopted(Outcome out, RunState run, Compacting compacting, String reason, Stage how) {
        SessionState session = out.session(run.ref());
        CompactionSettings settings = session.context().compaction();
        boolean mayTrim = switch (compacting.cause()) {
            case Compacting.Manual manual -> how == Stage.NOT_SENT;
            case Compacting.Auto auto -> how != Stage.REFUSED;
            case Compacting.Overflow overflow -> how != Stage.REFUSED;
        };
        if (mayTrim && Compactor.trimHelps(session.view(), settings)) {
            adoptTrim(out, run, compacting, settings);
            return Start.TOOK_OVER;
        }
        out.report(List.of(new CompactionFailed(run.ref(), compacting.trigger(), reason)));
        switch (compacting.cause()) {
            case Compacting.Auto auto -> {
                if (how != Stage.NOT_ADOPTED) {
                    out.put(run.withAutoCompactionFailed(true));
                    return Start.PROCEED;
                }
                // 摘要调用用掉的重试次数还给原调用。
                RunState resumed = run.withCompacting(null).withRetries(0).withAutoCompactionFailed(true);
                out.put(resumed);
                ModelSteps.callModel(out, resumed, null);
            }
            case Compacting.Overflow overflow -> RunEnd.finish(out, run.withCompacting(null), EndReason.FAILED, overflow.originalDetail());
            case Compacting.Manual manual -> RunEnd.finish(out, run.withCompacting(null), EndReason.FAILED, reason);
        }
        return Start.TOOK_OVER;
    }

    /** 只裁剪：不总结任何单元，全部保留，只去掉推理块、截短工具结果。 */
    private static void adoptTrim(Outcome out, RunState run, Compacting compacting, CompactionSettings settings) {
        List<Message> view = out.session(run.ref()).view();
        Plan plan = compacting.plan();
        adopt(out, run, compacting, Compactor.trimmed(view, settings), 0, plan.headUnits() + plan.keptUnits(), null, true);
    }
}
