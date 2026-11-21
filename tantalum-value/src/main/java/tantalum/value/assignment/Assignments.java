package tantalum.value.assignment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 会话上和交代有关的一块，只由事件改。{@code open} 是交代出去、结果还没回来的，按放出的顺序；
 * {@code unattendedRuns}、{@code unattendedAssignments} 是从上一次人说话以来自动开的轮数和放出的交代数，人一说话清零。
 */
public record Assignments(List<OpenAssignment> open, int unattendedRuns, int unattendedAssignments) {
    public static final Assignments NONE = new Assignments(List.of(), 0, 0);

    public Assignments {
        open = List.copyOf(open);
    }

    public Optional<OpenAssignment> find(String id) {
        return open.stream().filter(each -> each.id().equals(id)).findFirst();
    }

    public Assignments started(OpenAssignment assignment) {
        List<OpenAssignment> grown = new ArrayList<>(open);
        grown.add(assignment);
        return new Assignments(grown, unattendedRuns, unattendedAssignments + 1);
    }

    public Assignments ended(String id) {
        return new Assignments(open.stream().filter(each -> !each.id().equals(id)).toList(), unattendedRuns, unattendedAssignments);
    }

    /** 自动开了一轮。 */
    public Assignments ranUnattended() {
        return new Assignments(open, unattendedRuns + 1, unattendedAssignments);
    }

    /** 人说话了。 */
    public Assignments attended() {
        return unattendedRuns == 0 && unattendedAssignments == 0 ? this : new Assignments(open, 0, 0);
    }
}
