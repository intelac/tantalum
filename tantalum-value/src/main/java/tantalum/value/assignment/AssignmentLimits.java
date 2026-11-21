package tantalum.value.assignment;

import java.time.Duration;

/**
 * 交代的上限，内核按事件数出来，不看内容。{@code maxOpen} 是同时在外面的交代数；{@code maxUnattendedRuns}、
 * {@code maxUnattendedAssignments} 是从上一次人说话以来自动开的轮数和放出的交代数。
 * {@code deadline} 是一次交代最多跑多久，默认没有（null）：那一轮的每一步本来就有自己的超时，停着不动的只有等用户回答，
 * 那是正当的等人。管理员要“任何交代不超过多久”时才设。
 */
public record AssignmentLimits(int maxOpen, int maxUnattendedRuns, int maxUnattendedAssignments, Duration deadline) {
    /** 和 application.properties 的默认值一致。 */
    public static final AssignmentLimits DEFAULT = new AssignmentLimits(4, 10, 10, null);

    public AssignmentLimits {
        if (maxOpen < 0 || maxUnattendedRuns < 0 || maxUnattendedAssignments < 0 || deadline != null && (deadline.isNegative() || deadline.isZero())) {
            throw new IllegalArgumentException("assignment limits must not be negative and a deadline must be positive: " + maxOpen + ", "
                    + maxUnattendedRuns + ", " + maxUnattendedAssignments + ", " + deadline);
        }
    }
}
