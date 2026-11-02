package tantalum.kernel.step;

import java.util.List;
import java.util.Set;
import tantalum.kernel.hook.HookOutcome;
import tantalum.kernel.projection.PromptProjection;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.CallModel;
import tantalum.value.decision.Decision;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.Awaiting;
import tantalum.value.state.Compacting;
import tantalum.value.state.EndReason;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tantalum.value.state.UsageAnchor;

/** 模型调用的处理函数：调用前的钩子和压缩判断、收回复、被中断。 */
public final class ModelSteps {

    public static final String AWAITING_MODEL = "AwaitingModel";
    /** COMPLETED 的详情：回复因输出上限被截断。 */
    public static final String TRUNCATED = "reply truncated";

    private ModelSteps() {}

    static void callModel(Outcome out, RunState pending, WorkDone work) {
        callModel(out, pending, work, true);
    }

    /**
     * 调用模型前经过 {@code beforeModelCall}；待处理的 {@code steer} 在这里并入消息。
     * 放行后先看要不要自动压缩（规则 2）；{@code compactionAllowed} 为 false 是压缩刚结束后的那次调用，不再判断（规则 3.1）。
     */
    static void callModel(Outcome out, RunState pending, WorkDone work, boolean compactionAllowed) {
        RunState run = pending;
        for (Message steer : run.pendingSteers()) {
            out.append(run.ref(), steer);
        }
        run = run.withPendingSteers(List.of());
        out.put(run);

        HookOutcome hook = out.hooks.run(HookPoint.BEFORE_MODEL_CALL, out.state(),
                out.hookContext(run.ref()).withWork(work));
        out.report(hook.facts());
        switch (hook.result()) {
            case Stop stop -> RunEnd.finish(out, run, EndReason.STOPPED, stop.reason());
            case NeedWork need -> WorkSteps.start(out, run, need, HookPoint.BEFORE_MODEL_CALL);
            case Allow allow -> {
                if (compactionAllowed) {
                    if (CompactSteps.tryStart(out, run, allow.patch(), new Compacting.Auto()) == CompactSteps.Start.TOOK_OVER) {
                        return;
                    }
                    run = out.run(run.ref());
                }
                Prompt prompt = PromptProjection.project(out.session(run.ref()), allow.patch());
                CommandId id = out.nextCommandId();
                out.emit(new CallModel(id, run.ref(), prompt));
                out.await(run, AWAITING_MODEL, Set.of(id));
            }
        }
    }

    public static Decision modelReplied(StepContext context) {
        ModelReplied event = (ModelReplied) context.envelope().event();
        Outcome out = new Outcome(context);
        RunState run = context.run().withRetries(0).withOverflowRecovered(false);
        out.put(run);
        out.append(run.ref(), new Message(Role.ASSISTANT, event.content(), false));
        out.addUsage(run.ref(), event.usage());
        anchor(out, run, event.usage());

        List<ToolCallBlock> calls = event.content().stream()
                .filter(ToolCallBlock.class::isInstance).map(ToolCallBlock.class::cast).toList();
        if (calls.isEmpty()) {
            // 回复因输出上限被截断：运行照常完成，但带标记，界面据此提示。
            RunEnd.beforeEnd(out, run, null, event.stop() == StopReason.MAX_TOKENS ? TRUNCATED : null);
        } else {
            ToolSteps.start(out, run, calls);
        }
        return out.decision();
    }

    /** 被中断的回复。用户已看到的文本只在该运行仍是历史末尾时追加（会话已开新运行就只留在 journal）；
     * 中断的是运行正在等待的那次调用而运行还没结束（不是 {@code Cancel} 引起），运行以 CANCELLED 结束。 */
    public static Decision modelInterrupted(StepContext context) {
        ModelInterrupted event = (ModelInterrupted) context.envelope().event();
        RunState run = context.run();
        SessionState session = context.state().sessions().get(context.envelope().session());
        if (run == null || session == null) {
            return Decision.unchanged(context.state());
        }
        boolean awaited = run.phase() instanceof Awaiting awaiting && awaiting.commands().contains(event.command());
        boolean historyOpen = awaited || (run.isEnded() && session.isLatestRun(run.ref().run()));
        if (!historyOpen) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        // 被中断的是摘要调用时，半截摘要不是回复，不进历史。
        if (!event.text().isEmpty() && run.compacting() == null) {
            out.append(run.ref(), new Message(Role.ASSISTANT, List.of(new TextBlock(event.text())), true));
        }
        if (awaited) {
            RunEnd.finish(out, run, EndReason.CANCELLED, "model call interrupted");
        }
        return out.decision();
    }

    /** 规则 1 的锚点：有实测用量就记下 prompt 加输出和此刻的消息数，没有就清空，之后全部估算。 */
    private static void anchor(Outcome out, RunState run, TokenUsage usage) {
        SessionState session = out.session(run.ref());
        UsageAnchor anchor = usage == null ? null
                : new UsageAnchor(usage.promptTotal() + usage.output(), session.messages().size());
        out.put(session.withAnchor(anchor));
    }
}
