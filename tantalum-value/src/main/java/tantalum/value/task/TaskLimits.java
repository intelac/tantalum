package tantalum.value.task;

import java.time.Duration;

/**
 * 任务的上限，内核按事件数出来，不看内容。{@code maxOpen} 是同时在外面的任务数；{@code maxUnattendedRuns}、
 * {@code maxUnattendedTasks} 是从上一次人说话以来自动开的轮数和放出的任务数。
 * {@code deadline} 是一个任务最多跑多久，默认没有（null）：任务那一轮的每一步本来就有自己的超时，停着不动的只有等用户回答，
 * 那是正当的等人。管理员要“任何任务不超过多久”时才设。
 */
public record TaskLimits(int maxOpen, int maxUnattendedRuns, int maxUnattendedTasks, Duration deadline) {

    /** 和 application.properties 的默认值一致。 */
    public static final TaskLimits DEFAULT = new TaskLimits(4, 10, 10, null);

    public TaskLimits {
        if (maxOpen < 0 || maxUnattendedRuns < 0 || maxUnattendedTasks < 0 || deadline != null && (deadline.isNegative() || deadline.isZero())) {
            throw new IllegalArgumentException("task limits must not be negative and a deadline must be positive: " + maxOpen + ", "
                    + maxUnattendedRuns + ", " + maxUnattendedTasks + ", " + deadline);
        }
    }
}
