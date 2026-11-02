package tantalum.kernel.step;

import tantalum.kernel.table.StepContext;
import tantalum.value.command.AbortCommands;
import tantalum.value.decision.Decision;
import tantalum.value.event.CommandDropped;
import tantalum.value.state.Awaiting;
import tantalum.value.state.EndReason;
import tantalum.value.state.RunState;

/** 撤销和命令丢失的处理函数：运行立即结束，在途命令被中止。 */
public final class CancelSteps {

    public static final String INTERRUPTED_BY_RESTART = "interrupted by restart";

    private CancelSteps() {}

    /** 进程已经换过，没有在途的命令可中止，也不重新执行：写操作可能已经生效。 */
    public static Decision interrupted(StepContext context) {
        RunState run = context.run();
        if (run == null || run.ended()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        RunEnd.finish(out, run.withCompacting(null), EndReason.CANCELLED, INTERRUPTED_BY_RESTART);
        return out.decision();
    }

    public static Decision cancel(StepContext context) {
        return end(context, EndReason.CANCELLED, null);
    }

    public static Decision commandDropped(StepContext context) {
        CommandDropped event = (CommandDropped) context.envelope().event();
        return end(context, EndReason.FAILED, event.detail());
    }

    private static Decision end(StepContext context, EndReason reason, String detail) {
        RunState run = context.run();
        if (run == null || run.ended()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        if (run.phase() instanceof Awaiting awaiting && !awaiting.commands().isEmpty()) {
            out.emit(new AbortCommands(out.nextCommandId(), run.ref(), awaiting.commands()));
        }
        RunEnd.finish(out, run, reason, detail);
        return out.decision();
    }
}
