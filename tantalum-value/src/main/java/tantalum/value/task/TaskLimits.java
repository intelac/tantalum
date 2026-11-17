package tantalum.value.task;

import java.time.Duration;
import java.util.Objects;

/**
 * 任务的四个上限，内核按事件数出来，不看内容。{@code maxOpen} 是同时在外面的任务数；{@code maxUnattendedRuns}、
 * {@code maxUnattendedTasks} 是从上一次人说话以来自动开的轮数和放出的任务数；{@code deadline} 是一个任务最多跑多久。
 */
public record TaskLimits(int maxOpen, int maxUnattendedRuns, int maxUnattendedTasks, Duration deadline) {

    /** 和 application.properties 的默认值一致。 */
    public static final TaskLimits DEFAULT = new TaskLimits(4, 10, 10, Duration.ofMinutes(30));

    public TaskLimits {
        Objects.requireNonNull(deadline, "deadline");
        if (maxOpen < 0 || maxUnattendedRuns < 0 || maxUnattendedTasks < 0 || deadline.isNegative() || deadline.isZero()) {
            throw new IllegalArgumentException("task limits must not be negative and the deadline must be positive: " + maxOpen + ", "
                    + maxUnattendedRuns + ", " + maxUnattendedTasks + ", " + deadline);
        }
    }
}
