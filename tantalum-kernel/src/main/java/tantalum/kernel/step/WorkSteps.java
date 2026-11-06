package tantalum.kernel.step;

import java.util.List;
import java.util.Set;
import tantalum.kernel.hook.HookOutcome;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.DoWork;
import tantalum.value.decision.Decision;
import tantalum.value.decision.HookReported;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.model.Message;
import tantalum.value.state.EndReason;
import tantalum.value.state.RunState;

/** 插件工作的处理函数。工作完成后，按发起它的钩子位置回到原处。 */
public final class WorkSteps {

    public static final String AWAITING_WORK = "AwaitingWork";

    private WorkSteps() {}

    /**
     * 发起 {@code hook} 要求的工作。所有钩子要的工作都经过这里，轮数只在这里数：结束前按整个运行累计（续写给模型的循环每圈都有模型回复，
     * 按等待点数不住），其余按当前等待点连续计；{@code onWorkResult} 接着要的沿用原来的 {@code origin}。超过上限时运行失败，不再发工作。
     */
    static void start(Outcome out, RunState run, NeedWork need, String hook, HookPoint origin) {
        boolean atEnd = origin == HookPoint.BEFORE_RUN_END;
        int rounds = (atEnd ? run.endWorkRounds() : run.workRounds()) + 1;
        RunState counted = atEnd ? run.withEndWorkRounds(rounds) : run.withWorkRounds(rounds);
        if (rounds > out.maxWorkRounds) {
            // 写已经做完的轮数（等于上限），不写这次被拒绝的这一轮。
            int done = rounds - 1;
            out.report(List.of(new HookReported(run.ref(), hook,
                    "asked for more work after " + done + " rounds in a row at " + origin + " (limit " + out.maxWorkRounds + ")")));
            RunEnd.finish(out, counted.withWorkOrigin(null), EndReason.FAILED,
                    "plugin work did not settle: " + hook + " at " + origin + " after " + done + " rounds (limit " + out.maxWorkRounds + ")");
            return;
        }
        CommandId id = out.nextCommandId();
        out.emit(new DoWork(id, run.ref(), need.workType(), need.input()));
        out.await(counted.withWorkOrigin(origin), AWAITING_WORK, Set.of(id));
    }

    public static Decision workDone(StepContext context) {
        WorkDone event = (WorkDone) context.envelope().event();
        Outcome out = new Outcome(context);
        RunState run = context.run();
        HookPoint origin = run.workOrigin();
        // 工作要求记在会话里的东西先记下，钩子这一步就能看到（如工具审批的“这个会话里一直允许”）；失败的工作不记
        if (event.note() != null && !event.failed()) {
            out.put(out.session(run.ref()).withNote(event.workType(), event.note()));
        }

        HookOutcome hook = out.hooks.run(HookPoint.ON_WORK_RESULT, out.state(),
                out.hookContext(run.ref()).withWork(event));
        out.report(hook.facts());
        if (hook.result() instanceof NeedWork need) {
            start(out, run, need, hook.decidedBy(), origin);
            return out.decision();
        }
        RunState resumed = run.withWorkOrigin(null);
        out.put(resumed);
        resume(out, resumed, origin, event, hook.result() instanceof Stop stop ? stop.reason() : null);
        return out.decision();
    }

    /** 回到发起工作的位置。{@code stopped} 是 {@code onWorkResult} 的停止原因，放行时为空：拦工具时只拦这一个调用，其余位置结束运行。 */
    private static void resume(Outcome out, RunState run, HookPoint origin, WorkDone work, String stopped) {
        switch (origin) {
            case BEFORE_TOOL_CALL -> ToolSteps.resumeChecks(out, run, stopped);
            case BEFORE_MODEL_CALL -> {
                if (stopped != null) {
                    RunEnd.finish(out, run, EndReason.STOPPED, stopped);
                } else if (!continueWithText(out, run, work)) {
                    ModelSteps.callModel(out, run, work);
                }
            }
            case BEFORE_RUN_END -> {
                if (stopped != null) {
                    RunEnd.finish(out, run, EndReason.STOPPED, stopped);
                } else if (!continueWithText(out, run, work)) {
                    RunEnd.beforeEnd(out, run, work);
                }
            }
            case ON_FAILURE -> {
                if (stopped != null) {
                    RunEnd.finish(out, run, EndReason.STOPPED, stopped);
                } else {
                    RetrySteps.retryAfterWork(out, run, work);
                }
            }
            // 这两个位置的 NeedWork 不会发起工作（beforeCompact 当作拒绝，onWorkResult 沿用原位置）。
            case ON_WORK_RESULT, BEFORE_COMPACT -> throw new IllegalStateException(origin + " cannot be a work origin");
        }
    }

    /** 工作带了 {@code continueText}：作为用户消息追加后直接调模型，返回 true；没带返回 false，由调用方接着做。 */
    static boolean continueWithText(Outcome out, RunState run, WorkDone work) {
        if (work.continueText() == null) {
            return false;
        }
        out.append(run.ref(), Message.user(work.continueText()));
        ModelSteps.callModel(out, run, null);
        return true;
    }
}
