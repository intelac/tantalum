package tantalum.kernel.step;

import java.util.Set;
import tantalum.kernel.hook.HookOutcome;
import tantalum.kernel.hook.HookPoint;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.DoWork;
import tantalum.value.decision.Decision;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.model.Message;
import tantalum.value.state.EndReason;
import tantalum.value.state.RunState;

/** 插件工作的处理函数。工作完成后，按发起它的钩子位置回到原处。 */
public final class WorkSteps {

    static final String AWAITING_WORK = "AwaitingWork";

    private WorkSteps() {}

    static void start(Outcome out, RunState run, NeedWork need, HookPoint origin) {
        CommandId id = out.nextCommandId();
        out.emit(new DoWork(id, run.ref(), need.workType(), need.input()));
        RunState working = run.withWorkOrigin(origin.name());
        out.put(working);
        out.await(working, AWAITING_WORK, Set.of(id));
    }

    public static Decision workDone(StepContext context) {
        WorkDone event = (WorkDone) context.envelope().event();
        Outcome out = new Outcome(context);
        RunState run = context.run();
        HookPoint origin = HookPoint.valueOf(run.workOrigin());

        HookOutcome hook = out.hooks.run(HookPoint.ON_WORK_RESULT, out.state,
                out.hookContext(run.ref()).withWork(event));
        out.report(hook.facts());
        if (hook.result() instanceof NeedWork need) {
            start(out, run, need, origin);
            return out.decision();
        }
        RunState resumed = run.withWorkOrigin(null);
        out.put(resumed);
        if (hook.result() instanceof Stop stop) {
            if (origin == HookPoint.BEFORE_TOOL_CALL) {
                ToolSteps.resumeChecks(out, resumed, false, stop.reason());
            } else {
                RunEnd.finish(out, resumed, EndReason.STOPPED, stop.reason());
            }
            return out.decision();
        }
        resume(out, resumed, origin, event);
        return out.decision();
    }

    private static void resume(Outcome out, RunState run, HookPoint origin, WorkDone event) {
        if (origin == HookPoint.BEFORE_TOOL_CALL) {
            ToolSteps.resumeChecks(out, run, true, null);
        } else if (origin == HookPoint.ON_FAILURE) {
            RetrySteps.retryAfterWork(out, run, event);
        } else if (event.continueText() != null) {
            out.append(run.ref(), Message.user(event.continueText()));
            ModelSteps.callModel(out, run, null);
        } else if (origin == HookPoint.BEFORE_RUN_END) {
            RunEnd.beforeEnd(out, run, event);
        } else {
            ModelSteps.callModel(out, run, event);
        }
    }
}
