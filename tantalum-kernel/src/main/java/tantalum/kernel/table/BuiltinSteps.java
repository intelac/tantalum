package tantalum.kernel.table;

import tantalum.kernel.step.CancelSteps;
import tantalum.kernel.step.ModelSteps;
import tantalum.kernel.step.RetrySteps;
import tantalum.kernel.step.ToolSteps;
import tantalum.kernel.step.WorkSteps;
import tantalum.value.event.Cancel;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.TimerFired;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.event.WorkDone;

/** 注册内置的转移行。 */
public final class BuiltinSteps {

    private BuiltinSteps() {}

    public static void register(TransitionTable table) {
        table.register(TransitionTable.ANY_STATE, UserPrompt.TYPE, ModelSteps::userPrompt);
        table.register(TransitionTable.ANY_STATE, FollowUpSubmitted.TYPE, ModelSteps::followUp);
        table.register(TransitionTable.ANY_STATE, SteerSubmitted.TYPE, ModelSteps::steer);
        table.register(TransitionTable.ANY_STATE, Cancel.TYPE, CancelSteps::cancel);
        table.register(TransitionTable.ANY_STATE, CommandDropped.TYPE, CancelSteps::commandDropped);
        table.register(TransitionTable.ANY_STATE, ModelInterrupted.TYPE, ModelSteps::modelInterrupted);

        table.register("AwaitingContext", ContextAssembled.TYPE, ModelSteps::contextAssembled);
        table.register("AwaitingModel", ModelReplied.TYPE, ModelSteps::modelReplied);
        table.register("AwaitingModel", ModelFailed.TYPE, RetrySteps::modelFailed);
        table.register("AwaitingTools", ToolFinished.TYPE, ToolSteps::toolFinished);
        table.register("AwaitingRetry", TimerFired.TYPE, RetrySteps::timerFired);
        table.register("AwaitingWork", WorkDone.TYPE, WorkSteps::workDone);
    }
}
