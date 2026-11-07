package tantalum.kernel.step;

import tantalum.kernel.table.StepContext;
import tantalum.value.command.AbortCommands;
import tantalum.value.decision.Decision;
import tantalum.value.event.CommandDropped;
import tantalum.value.state.Awaiting;
import tantalum.value.state.AwaitingAnswer;
import tantalum.value.state.EndReason;
import tantalum.value.state.RunState;

/** 撤销和命令丢失的处理函数：运行立即结束，在途命令被中止。 */
public final class CancelSteps {

    public static final String INTERRUPTED_BY_RESTART = "interrupted by restart";

    private CancelSteps() {}

    /**
     * 进程已经换过，没有在途的命令可中止，也不重新执行：写操作可能已经生效。
     * 等用户回答的运行没有在途命令，不收尾，照旧等（shell 启动时也不会给它发这个事件）。
     */
    public static Decision interrupted(StepContext context) {
        RunState run = context.run();
        if (run == null || run.isEnded() || run.phase() instanceof AwaitingAnswer) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        RunEnd.finish(out, run, EndReason.CANCELLED, INTERRUPTED_BY_RESTART);
        return out.decision();
    }

    public static Decision cancel(StepContext context) {
        return end(context, EndReason.CANCELLED, null);
    }

    /** 只有运行正在等的命令被丢才算失败；别的命令（迟到的、别的阶段的）被丢时运行不变。 */
    public static Decision commandDropped(StepContext context) {
        CommandDropped event = (CommandDropped) context.envelope().event();
        RunState run = context.run();
        boolean awaited = run != null && run.phase() instanceof Awaiting awaiting && awaiting.commands().contains(event.command());
        if (!awaited) {
            return Decision.unchanged(context.state());
        }
        return end(context, EndReason.FAILED, event.detail());
    }

    private static Decision end(StepContext context, EndReason reason, String detail) {
        RunState run = context.run();
        if (run == null || run.isEnded()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        if (run.phase() instanceof Awaiting awaiting && !awaiting.commands().isEmpty()) {
            out.emit(new AbortCommands(out.nextCommandId(), run.ref(), awaiting.commands()));
        } else if (run.phase() instanceof AwaitingAnswer waiting) {
            AnswerSteps.cancelled(out, run, waiting);
        }
        RunEnd.finish(out, run, reason, detail);
        return out.decision();
    }
}
