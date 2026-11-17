package tantalum.value.decision;

import java.util.Objects;
import tantalum.value.id.RunRef;
import tantalum.value.task.TaskOutcome;

/**
 * 放出的一个任务有了结果。{@code run} 是看到这个结果的那一轮（正在跑的，或者为它新开的）；{@code paused} 为真时
 * 自动开的轮数到了上限，结果只记进历史、没有开运行，等人说话。
 */
public record TaskSettled(RunRef run, String task, TaskOutcome outcome, boolean paused) implements Fact {

    public TaskSettled {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(outcome, "outcome");
    }

    @Override
    public String type() {
        return "TaskSettled";
    }
}
