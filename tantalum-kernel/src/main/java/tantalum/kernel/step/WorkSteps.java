package tantalum.kernel.step;

import java.util.Set;
import tantalum.kernel.hook.HookOutcome;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.DoWork;
import tantalum.value.decision.Decision;
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

    static void start(Outcome out, RunState run, NeedWork need, HookPoint origin) {
        CommandId id = out.nextCommandId();
        out.emit(new DoWork(id, run.ref(), need.workType(), need.input()));
        out.await(run.withWorkOrigin(origin), AWAITING_WORK, Set.of(id));
    }

    public static Decision workDone(StepContext context) {
        WorkDone event = (WorkDone) context.envelope().event();
        Outcome out = new Outcome(context);
        RunState run = context.run();
        HookPoint origin = run.workOrigin();

        HookOutcome hook = out.hooks.run(HookPoint.ON_WORK_RESULT, out.state(),
                out.hookContext(run.ref()).withWork(event));
        out.report(hook.facts());
        if (hook.result() instanceof NeedWork need) {
            start(out, run, need, origin);
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
