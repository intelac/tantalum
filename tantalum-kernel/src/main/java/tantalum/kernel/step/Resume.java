package tantalum.kernel.step;

import tantalum.value.event.WorkDone;
import tantalum.value.hook.HookPoint;
import tantalum.value.model.Message;
import tantalum.value.state.EndReason;
import tantalum.value.state.RunState;

/**
 * 回到钩子发起等待的位置：插件工作做完（{@link WorkSteps}）和用户回答了（{@link AnswerSteps}）都从这里回去。
 * {@code stopped} 是钩子的停止原因，放行时为空：拦工具时只拦这一个调用，其余位置结束运行。
 * {@code work} 是刚完成的工作，回答时为 null（只有工作能带 {@code continueText}）。
 */
final class Resume {

    private Resume() {}

    static void at(Outcome out, RunState run, HookPoint origin, WorkDone work, String stopped) {
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
            // 这三个位置不会成为发起位置：beforeCompact 把 NeedWork、Ask 当作拒绝，onWorkResult 和 onAnswer 沿用原位置。
            case ON_WORK_RESULT, BEFORE_COMPACT, ON_ANSWER -> throw new IllegalStateException(origin + " cannot be a wait origin");
        }
    }

    /** 工作带了 {@code continueText}：作为用户消息追加后直接调模型，返回 true；没带（或不是工作）返回 false，由调用方接着做。 */
    static boolean continueWithText(Outcome out, RunState run, WorkDone work) {
        if (work == null || work.continueText() == null) {
            return false;
        }
        out.append(run.ref(), Message.user(work.continueText()));
        // 钩子要看到是哪个工作的续写
        ModelSteps.callModel(out, run, work);
        return true;
    }
}
