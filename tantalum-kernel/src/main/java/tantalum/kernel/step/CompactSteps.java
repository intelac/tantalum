package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tantalum.kernel.compaction.Compactor;
import tantalum.kernel.compaction.SummaryPrompt;
import tantalum.kernel.compaction.TokenEstimate;
import tantalum.kernel.hook.HookOutcome;
import tantalum.kernel.hook.HookPoint;
import tantalum.kernel.projection.UsageProjection;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.CallModel;
import tantalum.value.decision.CompactionFailed;
import tantalum.value.decision.ContextCompacted;
import tantalum.value.decision.Decision;
import tantalum.value.event.CompactRequested;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.hook.Allow;
import tantalum.value.hook.PromptPatch;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.Compacting;
import tantalum.value.state.Compacting.Trigger;
import tantalum.value.state.Compaction;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.EndReason;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.Plan;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** 上下文压缩的处理函数：发摘要调用、收摘要、失败处理。规则见 context-compaction 需求。 */
public final class CompactSteps {

    static final String AWAITING_SUMMARY = "AwaitingSummary";
    public static final String COMPACTED = "compacted";
    public static final String NOTHING_TO_COMPACT = "nothing to compact";

    private CompactSteps() {}

    /**
     * 调模型前的压缩判断。返回 null 表示这里已经接管（发了摘要调用或结束了运行），调用方直接返回；
     * 否则返回要继续用的运行状态（自动压缩被钩子拒绝时带上 {@code autoCompactionFailed}）。
     */
    static RunState tryStart(Outcome out, RunState run, PromptPatch patch, Trigger trigger, String manual, String originalDetail) {
        if (trigger == Trigger.AUTO && run.autoCompactionFailed()) {
            return run;
        }
        SessionState session = out.session(run.ref());
        String system = run.system() + patch.systemAppend();
        List<ToolSpec> tools = new ArrayList<>(run.toolSpecs());
        tools.addAll(patch.addTools());
        Optional<Plan> planned = Compactor.plan(session, system, tools, trigger);
        if (planned.isEmpty()) {
            return run;
        }
        Plan plan = planned.orElseThrow();
        Compacting compacting = new Compacting(trigger, plan, manual, originalDetail);

        HookOutcome hook = out.hooks.run(HookPoint.BEFORE_COMPACT, out.state, out.hookContext(run.ref()).withPlan(plan));
        out.report(hook.facts());
        if (!(hook.result() instanceof Allow)) {
            String reason = hook.result() instanceof Stop stop ? stop.reason() : "beforeCompact asked for work, which is not supported";
            return refused(out, run, compacting, reason);
        }

        CompactionSettings settings = session.context().compaction();
        Message instruction = SummaryPrompt.instruction(false, manual);
        long fixed = TokenEstimate.ofText(system) + TokenEstimate.ofTools(tools) + TokenEstimate.ofMessage(instruction);
        Compactor.Head head = Compactor.headForSummary(plan, session.view(), settings, fixed);
        List<Message> messages = new ArrayList<>(head.messages());
        messages.add(SummaryPrompt.instruction(head.dropped(), manual));
        Prompt prompt = new Prompt(system, tools, messages, new Prompt.Output(false, settings.summaryMaxTokens()));

        RunState waiting = run.withCompacting(compacting);
        out.put(waiting);
        CommandId id = out.nextCommandId();
        out.emit(new CallModel(id, run.ref(), prompt));
        out.await(waiting, AWAITING_SUMMARY, Set.of(id));
        return null;
    }

    /** 钩子拒绝：自动压缩标记后让调用方继续；其他触发方式结束运行。 */
    private static RunState refused(Outcome out, RunState run, Compacting compacting, String reason) {
        out.report(List.of(new CompactionFailed(run.ref(), compacting.trigger(), reason)));
        if (compacting.trigger() == Trigger.AUTO) {
            RunState marked = run.withAutoCompactionFailed(true);
            out.put(marked);
            return marked;
        }
        RunEnd.finish(out, run, EndReason.FAILED,
                compacting.trigger() == Trigger.OVERFLOW ? compacting.originalDetail() : reason);
        return null;
    }

    /**
     * 手动压缩（决定 D4）：空闲会话上开一个特殊运行，不追加用户消息，不组装上下文（用会话冻结的 system 和工具）。
     * 会话正在运行、还没有上下文或视图为空时忽略；接口在发布前已把这些情况拦成 409，只有竞争才会走到这里。
     */
    public static Decision requested(StepContext context) {
        CompactRequested event = (CompactRequested) context.envelope().event();
        EventEnvelope envelope = context.envelope();
        SessionState session = context.state().sessions().get(envelope.session());
        if (session == null || session.activeRun() != null || session.context() == null || session.view().isEmpty()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        RunRef ref = new RunRef(envelope.principal(), envelope.session(), new RunId("run-" + envelope.seq()));
        out.state = out.state.with(session.withActiveRun(ref.run()));
        RunState run = RunState.start(ref, new OwnerRef.OfSession(envelope.session()))
                .withContext(session.context().system(), session.context().tools());
        out.put(run);
        RunState notStarted = tryStart(out, run, PromptPatch.NONE, Trigger.MANUAL, event.instructions(), null);
        if (notStarted != null) {
            RunEnd.finish(out, notStarted, EndReason.FAILED, NOTHING_TO_COMPACT);
        }
        return out.decision();
    }

    public static Decision summaryReplied(StepContext context) {
        ModelReplied event = (ModelReplied) context.envelope().event();
        Outcome out = new Outcome(context);
        RunState run = context.run();
        Compacting compacting = run.compacting();
        out.state = UsageProjection.apply(out.state, run.ref().principal(), context.envelope().at(), event.usage());

        Plan plan = compacting.plan();
        long request = plan.request() == null ? 0 : TokenEstimate.ofMessage(plan.request());
        String reason = SummaryPrompt.validate(event.content(), event.stop(), plan.headEstimate(), request);
        if (reason != null) {
            failed(out, run, compacting, reason);
            return out.decision();
        }

        SessionState session = out.session(run.ref());
        List<Message> view = session.view();
        List<Message> replacement = Compactor.replacement(compacting.plan(), view, SummaryPrompt.text(event.content()),
                session.context().compaction());
        out.state = out.state.with(session.compacted(new Compaction(replacement, session.messages().size())));
        out.report(List.of(new ContextCompacted(run.ref(), compacting.trigger(), compacting.plan().headUnits(),
                compacting.plan().keptUnits(), TokenEstimate.ofMessages(view), TokenEstimate.ofMessages(replacement), event.usage())));

        RunState resumed = run.withCompacting(null);
        out.put(resumed);
        if (compacting.trigger() == Trigger.MANUAL) {
            RunEnd.finish(out, resumed, EndReason.COMPLETED, COMPACTED);
        } else {
            ModelSteps.callModel(out, resumed, null, false);
        }
        return out.decision();
    }

    public static Decision summaryFailed(StepContext context) {
        ModelFailed event = (ModelFailed) context.envelope().event();
        Outcome out = new Outcome(context);
        RunState run = context.run();
        failed(out, run, run.compacting(), "summary call failed: " + event.reason() + " " + event.detail());
        return out.decision();
    }

    /** 规则 8：自动 → 继续原调用且本运行不再自动压缩；超长恢复 → 以原始错误失败；手动 → 以原因失败。 */
    private static void failed(Outcome out, RunState run, Compacting compacting, String reason) {
        out.report(List.of(new CompactionFailed(run.ref(), compacting.trigger(), reason)));
        RunState resumed = run.withCompacting(null);
        switch (compacting.trigger()) {
            case AUTO -> {
                resumed = resumed.withAutoCompactionFailed(true);
                out.put(resumed);
                ModelSteps.callModel(out, resumed, null, false);
            }
            case OVERFLOW -> RunEnd.finish(out, resumed, EndReason.FAILED, compacting.originalDetail());
            case MANUAL -> RunEnd.finish(out, resumed, EndReason.FAILED, reason);
        }
    }
}
