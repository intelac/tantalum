package tantalum.kernel.step;

import tantalum.kernel.table.TransitionTable;
import tantalum.value.decision.Decision;
import tantalum.value.event.Assignment;
import tantalum.value.event.AssignmentCancelled;
import tantalum.value.event.Report;
import tantalum.value.event.SessionCreated;
import tantalum.value.event.StopRequested;
import tantalum.value.event.Answered;
import tantalum.value.event.Cancel;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.CompactRequested;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.QueueChanged;
import tantalum.value.event.RunInterrupted;
import tantalum.value.event.SessionRenamed;
import tantalum.value.event.TimerFired;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.event.WorkDone;

/** 注册内置的转移行。 */
public final class BuiltinSteps {

    private BuiltinSteps() {}

    public static void register(TransitionTable table) {
        table.register(TransitionTable.ANY_STATE, SessionCreated.TYPE, SessionSteps::created);
        // 对会话说的话：每种是自己的事件类型，都走同一个处理函数，怎么办由 InputPolicy 那张表定
        table.register(TransitionTable.ANY_STATE, UserPrompt.TYPE, InboxSteps::arrived);
        table.register(TransitionTable.ANY_STATE, Assignment.TYPE, InboxSteps::arrived);
        table.register(TransitionTable.ANY_STATE, Report.TYPE, InboxSteps::arrived);
        table.register(TransitionTable.ANY_STATE, QueueChanged.TYPE, QueueSteps::changed);
        table.register(TransitionTable.ANY_STATE, Cancel.TYPE, CancelSteps::cancel);
        table.register(TransitionTable.ANY_STATE, CommandDropped.TYPE, CancelSteps::commandDropped);
        table.register(TransitionTable.ANY_STATE, RunInterrupted.TYPE, CancelSteps::interrupted);
        table.register(TransitionTable.ANY_STATE, ModelInterrupted.TYPE, ModelSteps::modelInterrupted);
        table.register(TransitionTable.ANY_STATE, CompactRequested.TYPE, CompactSteps::requested);
        // 改名只是展示用的名字：进账，内核不改状态
        table.register(TransitionTable.ANY_STATE, SessionRenamed.TYPE, context -> Decision.unchanged(context.state()));

        // 撤下文字：只换字，任何状态下都能做，不改运行的走向
        table.register(TransitionTable.ANY_STATE, tantalum.value.event.TextWithdrawn.TYPE, WithdrawSteps::withdrawn);

        table.register(ContextSteps.AWAITING_CONTEXT, ContextAssembled.TYPE, ContextSteps::contextAssembled);
        table.register(ModelSteps.AWAITING_MODEL, ModelReplied.TYPE, ModelSteps::modelReplied);
        table.register(ModelSteps.AWAITING_MODEL, ModelFailed.TYPE, RetrySteps::modelFailed);
        table.register(CompactSteps.AWAITING_SUMMARY, ModelReplied.TYPE, CompactSteps::summaryReplied);
        table.register(CompactSteps.AWAITING_SUMMARY, ModelFailed.TYPE, CompactSteps::summaryFailed);
        table.register(CompactSteps.AWAITING_SUMMARY_RETRY, TimerFired.TYPE, CompactSteps::summaryRetry);
        table.register(ToolSteps.AWAITING_TOOLS, ToolFinished.TYPE, ToolSteps::toolFinished);
        table.register(RetrySteps.AWAITING_RETRY, TimerFired.TYPE, RetrySteps::timerFired);
        table.register(WorkSteps.AWAITING_WORK, WorkDone.TYPE, WorkSteps::workDone);
        // 等用户回答：回答、期限到；别的阶段收到回答只记事实
        table.register(AnswerSteps.AWAITING_ANSWER, Answered.TYPE, AnswerSteps::answered);
        table.register(AnswerSteps.AWAITING_ANSWER, TimerFired.TYPE, AnswerSteps::expired);
        table.register(TransitionTable.ANY_STATE, Answered.TYPE, AnswerSteps::ignored);
        // 交代：用户要停；交代的一方不要它了；期限到（没人等的定时器都走这一行，不是交代的不动）
        table.register(TransitionTable.ANY_STATE, StopRequested.TYPE, AssignmentSteps::stopRequested);
        table.register(TransitionTable.ANY_STATE, AssignmentCancelled.TYPE, AssignmentSteps::cancelled);
        table.register(TransitionTable.ANY_STATE, TimerFired.TYPE, AssignmentSteps::timerFired);
    }
}
