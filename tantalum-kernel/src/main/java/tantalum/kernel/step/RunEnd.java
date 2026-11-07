package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.List;
import tantalum.kernel.hook.HookOutcome;
import tantalum.kernel.table.StepContext;
import tantalum.kernel.tool.ToolPairing;
import tantalum.value.decision.Decision;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Ask;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChangeBlock;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.Role;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** 运行的结束路径：结束前的钩子、工具配对、取下一条 {@code follow-up}。 */
public final class RunEnd {

    /** 换模型前压缩失败时运行的详情，后面接新模型的 {@code 路线/模型}；画面据此写“换到 … 之前压缩失败”。 */
    public static final String SWITCH_COMPACTION_FAILED = "compaction failed before switching to ";

    private RunEnd() {}

    /** 处理函数抛异常时使用，只让该运行失败。 */
    public static Decision fail(StepContext context, RuntimeException failure) {
        Outcome out = new Outcome(context);
        finish(out, context.run(), EndReason.FAILED, failure.toString());
        return out.decision();
    }

    static void beforeEnd(Outcome out, RunState run, WorkDone work) {
        beforeEnd(out, run, work, null);
    }

    /** 模型不再要工具时调用；{@code work} 是刚完成的工作，钩子据此判断是否放行；{@code detail} 随 COMPLETED 记录，如回复被截断。 */
    static void beforeEnd(Outcome out, RunState run, WorkDone work, String detail) {
        if (!run.pendingSteers().isEmpty()) {
            ModelSteps.callModel(out, run, work);
            return;
        }
        HookOutcome hook = out.hooks.run(HookPoint.BEFORE_RUN_END, out.state(),
                out.hookContext(run.ref()).withWork(work));
        out.report(hook.facts());
        switch (hook.result()) {
            case Stop stop -> finish(out, run, EndReason.STOPPED, stop.reason());
            case NeedWork need -> WorkSteps.start(out, run, need, hook.decidedBy(), HookPoint.BEFORE_RUN_END);
            case Ask ask -> AnswerSteps.ask(out, run, ask, hook.decidedBy(), HookPoint.BEFORE_RUN_END);
            default -> finish(out, run, EndReason.COMPLETED, detail);
        }
    }

    /**
     * 换到窗口更小的模型之前的压缩没成（model-picker M10）：这个运行失败，会话的模型退回上一个。已经追加的换模型说明不能删，
     * 再追加一条反方向的，下一次调用时模型看到的说明和实际一致。纯函数，回放结果相同。
     */
    static void switchFailed(Outcome out, RunState run) {
        ModelChoice now = run.model();
        ModelChoice back = run.modelBefore();
        SessionState session = out.session(run.ref());
        out.put(session.append(new Message(Role.SYSTEM, List.of(new ModelChangeBlock(now.label(), back.label())), false)).withModel(back));
        finish(out, run.withModel(now, null), EndReason.FAILED, SWITCH_COMPACTION_FAILED + now.label());
    }

    /**
     * 结束运行。未配对的工具调用补上合成结果；模型还没看到的插话追加为用户消息，下一个运行会看到。
     * {@code compacting} 原样保留：在等摘要时结束的运行，迟到的摘要回复据此不进历史。
     */
    static void finish(Outcome out, RunState run, EndReason reason, String detail) {
        if (run.tools() != null && !run.tools().allFinished()) {
            List<ContentBlock> results = new ArrayList<>(ToolPairing.complete(run.tools()));
            out.append(run.ref(), new Message(Role.USER, results, false));
        }
        for (Message steer : run.pendingSteers()) {
            out.append(run.ref(), steer);
        }
        out.enter(run.withTools(null).withPendingSteers(List.of()),
                new Ended(reason, detail));

        SessionState session = out.session(run.ref()).endRun();
        out.put(session);
        if (!session.followUps().isEmpty()) {
            Message next = session.followUps().getFirst();
            ModelChoice requested = session.followUpModels().getFirst();
            int size = session.followUps().size();
            out.put(session.withFollowUps(session.followUps().subList(1, size), session.followUpModels().subList(1, size)));
            SubmitSteps.startRun(out, new OwnerRef.OfSession(session.id()), next, requested);
        }
    }
}
