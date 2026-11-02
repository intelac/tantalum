package tantalum.kernel.step;

import tantalum.kernel.table.TransitionTable;
import tantalum.value.event.Cancel;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.CompactRequested;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.RunInterrupted;
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
        table.register(TransitionTable.ANY_STATE, Cancel.TYPE, CancelSteps::cancel);
        table.register(TransitionTable.ANY_STATE, CommandDropped.TYPE, CancelSteps::commandDropped);
        table.register(TransitionTable.ANY_STATE, RunInterrupted.TYPE, CancelSteps::interrupted);
        table.register(TransitionTable.ANY_STATE, ModelInterrupted.TYPE, ModelSteps::modelInterrupted);
        table.register(TransitionTable.ANY_STATE, CompactRequested.TYPE, CompactSteps::requested);

        table.register(ContextSteps.AWAITING_CONTEXT, ContextAssembled.TYPE, ContextSteps::contextAssembled);
        table.register(ModelSteps.AWAITING_MODEL, ModelReplied.TYPE, ModelSteps::modelReplied);
        table.register(ModelSteps.AWAITING_MODEL, ModelFailed.TYPE, RetrySteps::modelFailed);
        table.register(CompactSteps.AWAITING_SUMMARY, ModelReplied.TYPE, CompactSteps::summaryReplied);
        table.register(CompactSteps.AWAITING_SUMMARY, ModelFailed.TYPE, CompactSteps::summaryFailed);
        table.register(CompactSteps.AWAITING_SUMMARY_RETRY, TimerFired.TYPE, CompactSteps::summaryRetry);
        table.register(ToolSteps.AWAITING_TOOLS, ToolFinished.TYPE, ToolSteps::toolFinished);
        table.register(RetrySteps.AWAITING_RETRY, TimerFired.TYPE, RetrySteps::timerFired);
        table.register(WorkSteps.AWAITING_WORK, WorkDone.TYPE, WorkSteps::workDone);
    }
}
