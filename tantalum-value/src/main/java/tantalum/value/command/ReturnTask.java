package tantalum.value.command;

import java.util.Objects;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.task.TaskOutcome;

/** 任务结束，把结果交回放出它的会话 {@code parent}：处理器往那个会话放一条 {@code TaskEnded}。{@code run} 是任务自己的那一轮。 */
public record ReturnTask(CommandId id, RunRef run, SessionId parent, String task, TaskOutcome outcome, String text) implements Command {

    public ReturnTask {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(outcome, "outcome");
    }
}
