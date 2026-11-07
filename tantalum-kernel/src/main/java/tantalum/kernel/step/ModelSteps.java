package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tantalum.kernel.attachment.ImageBudget;
import tantalum.kernel.hook.HookOutcome;
import tantalum.kernel.hook.HookPatches;
import tantalum.kernel.projection.PromptProjection;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.CallModel;
import tantalum.value.decision.Decision;
import tantalum.value.decision.ImagesOmitted;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.Ask;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;
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
     * 放行后先看要不要自动压缩（规则 2）；{@code compactionAllowed} 为 false 是压缩刚结束后的那次调用，不再判断（规则 3.1）；
     * 调用方在冻结上下文之前用 {@code SessionState.justCompacted()} 算出它。
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
            case NeedWork need -> WorkSteps.start(out, run, need, hook.decidedBy(), HookPoint.BEFORE_MODEL_CALL);
            case Ask ask -> AnswerSteps.ask(out, run, ask, hook.decidedBy(), HookPoint.BEFORE_MODEL_CALL);
            case Allow allow -> {
                // 钩子的补丁先进历史：压缩看到的历史已经带着它，摘要调用和正常调用都从同一份会话投影
                HookPatches.Applied patched = HookPatches.apply(out.session(run.ref()), hook.facts(), allow.patch(), run.ref());
                if (patched.session() != out.session(run.ref())) {
                    out.put(patched.session());
                }
                out.report(patched.facts());
                if (compactionAllowed) {
                    if (CompactSteps.tryStart(out, run, new Compacting.Auto()) == CompactSteps.Start.TOOK_OVER) {
                        return;
                    }
                    run = out.run(run.ref());
                }
                Prompt prompt = PromptProjection.project(out.session(run.ref())).withModel(model(out, run));
                CommandId id = emitCall(out, run.ref(), prompt);
                out.await(run, AWAITING_MODEL, Set.of(id));
            }
        }
    }

    /**
     * 按图片预算省略后发出模型调用，普通调用和摘要调用都走这里。新省略的图片记进会话（只增不减），报一条
     * {@link ImagesOmitted}；回放时重新算出同样的省略。
     */
    static CommandId emitCall(Outcome out, RunRef run, Prompt prompt) {
        SessionState session = out.session(run);
        ImageBudget.Applied applied = ImageBudget.apply(PromptProjection.placeSystemUpdates(prompt.messages()), session.omittedImages());
        if (!applied.newlyOmitted().isEmpty()) {
            List<String> all = new ArrayList<>(session.omittedImages());
            all.addAll(applied.newlyOmitted());
            out.put(session.withOmittedImages(all));
            out.report(List.of(new ImagesOmitted(run, applied.newlyOmitted())));
        }
        CommandId id = out.nextCommandId();
        out.emit(new CallModel(id, run, new Prompt(prompt.system(), prompt.tools(), applied.messages(), prompt.output(), prompt.model()),
                applied.omitted()));
        return id;
    }

    public static Decision modelReplied(StepContext context) {
        ModelReplied event = (ModelReplied) context.envelope().event();
        Outcome out = new Outcome(context);
        // 收到回复就越过了模型调用前这个等待点，工作轮数清零。
        RunState run = context.run().withRetries(0).withOverflowRecovered(false).withWorkRounds(0);
        // 新模型回复了：压缩前的摘要不再需要换掉的那个模型
        if (run.modelBefore() != null) {
            run = run.withModel(run.model(), null);
        }
        out.put(run);
        out.append(run.ref(), new Message(Role.ASSISTANT, event.content(), false));
        out.addUsage(run.ref(), event.usage());
        anchor(out, run, event.usage());

        List<ToolCallBlock> calls = event.content().stream()
                .filter(ToolCallBlock.class::isInstance).map(ToolCallBlock.class::cast).toList();
        if (calls.isEmpty() || event.stop() == StopReason.MAX_TOKENS) {
            // 回复因输出上限被截断：运行照常完成，但带标记，界面据此提示。带着工具调用被截断时参数多半不完整，不执行
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

    /** 这次调用用的模型：运行定下的；手动压缩这种不组装上下文的运行用会话现在的。都没有时为 null，用默认路线。 */
    static ModelChoice model(Outcome out, RunState run) {
        return run.model() != null ? run.model() : out.session(run.ref()).model();
    }

    /** 规则 1 的锚点：有实测用量就记下 prompt 加输出和此刻的消息数，没有就清空，之后全部估算。 */
    private static void anchor(Outcome out, RunState run, TokenUsage usage) {
        SessionState session = out.session(run.ref());
        UsageAnchor anchor = usage == null ? null
                : new UsageAnchor(usage.promptTotal() + usage.output(), session.messages().size());
        out.put(session.withAnchor(anchor));
    }
}
