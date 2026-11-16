package tantalum.kernel.step;

import tantalum.kernel.table.TransitionTable;
import tantalum.value.decision.Decision;
import tantalum.value.event.Answered;
import tantalum.value.event.Cancel;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.CompactRequested;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.DelegateCancelled;
import tantalum.value.event.DelegateReturned;
import tantalum.value.event.Delegated;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.QueueChanged;
import tantalum.value.event.Recovered;
import tantalum.value.event.RunInterrupted;
import tantalum.value.event.SessionRenamed;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.TimerFired;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.event.WorkDone;

/** 注册内置的转移行。 */
public final class BuiltinSteps {

    private BuiltinSteps() {}

    public static void register(TransitionTable table) {
        table.register(TransitionTable.ANY_STATE, UserPrompt.TYPE, SubmitSteps::userPrompt);
        table.register(TransitionTable.ANY_STATE, FollowUpSubmitted.TYPE, SubmitSteps::followUp);
        table.register(TransitionTable.ANY_STATE, SteerSubmitted.TYPE, SubmitSteps::steer);
        table.register(TransitionTable.ANY_STATE, QueueChanged.TYPE, QueueSteps::changed);
        table.register(TransitionTable.ANY_STATE, Cancel.TYPE, CancelSteps::cancel);
        table.register(TransitionTable.ANY_STATE, CommandDropped.TYPE, CancelSteps::commandDropped);
        table.register(TransitionTable.ANY_STATE, RunInterrupted.TYPE, CancelSteps::interrupted);
        table.register(TransitionTable.ANY_STATE, ModelInterrupted.TYPE, ModelSteps::modelInterrupted);
        table.register(TransitionTable.ANY_STATE, CompactRequested.TYPE, CompactSteps::requested);
        // 改名只是展示用的名字：进账，内核不改状态
        table.register(TransitionTable.ANY_STATE, SessionRenamed.TYPE, context -> Decision.unchanged(context.state()));

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
        // 委派（kernel-delegation）：交活进子会话；交回填槽位或作为汇报；期限到；重启后重发
        table.register(TransitionTable.ANY_STATE, Delegated.TYPE, DelegateSteps::delegated);
        table.register(ToolSteps.AWAITING_TOOLS, DelegateReturned.TYPE, DelegateSteps::returned);
        table.register(DelegateSteps.AWAITING_DELEGATE, DelegateReturned.TYPE, DelegateSteps::returned);
        table.register(TransitionTable.ANY_STATE, DelegateReturned.TYPE, DelegateSteps::reported);
        table.register(DelegateSteps.AWAITING_DELEGATE, TimerFired.TYPE, DelegateSteps::expired);
        table.register(TransitionTable.ANY_STATE, Recovered.TYPE, DelegateSteps::recovered);
        table.register(TransitionTable.ANY_STATE, DelegateCancelled.TYPE, DelegateSteps::cancelled);
    }
}
