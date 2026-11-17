package tantalum.value.event;

import java.util.Objects;
import tantalum.value.task.TaskOutcome;

/**
 * 放出去的一个任务结束了，进放出它的会话。不是人说的话：只由 {@code ReturnTask} 的处理器或启动时的核对放进来。
 * {@code text} 是任务最后的回答（完成时）或结束的详情，可以为 null。同一个任务到第二次时名单里已经没有它，什么都不改。
 */
public record TaskEnded(String task, TaskOutcome outcome, String text) implements Event {

    public static final String TYPE = "TaskEnded";

    public TaskEnded {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(outcome, "outcome");
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
