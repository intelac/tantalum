package tantalum.value.assignment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 会话上和交代有关的一块，只由事件改。{@code open} 是交代出去、结果还没回来的，按放出的顺序；
 * {@code unattendedRuns}、{@code unattendedAssignments} 是从上一次人说话以来自动开的轮数和放出的交代数，人一说话清零。
 */
// taken：这个会话最近收下的送达编号（交代 assign:、记一笔 notice:），最多 TAKEN_MAX 个，按先后；重复送来的据此认出（dispatch）。
public record Assignments(List<OpenAssignment> open, int unattendedRuns, int unattendedAssignments, List<String> taken) {
    public static final Assignments NONE = new Assignments(List.of(), 0, 0, List.of());
    public static final int TAKEN_MAX = 64;

    public Assignments {
        open = List.copyOf(open);
        taken = taken == null ? List.of() : List.copyOf(taken);
    }

    public Optional<OpenAssignment> find(String id) {
        return open.stream().filter(each -> each.id().equals(id)).findFirst();
    }

    public Assignments started(OpenAssignment assignment) {
        List<OpenAssignment> grown = new ArrayList<>(open);
        grown.add(assignment);
        return new Assignments(grown, unattendedRuns, unattendedAssignments + 1, taken);
    }

    public Assignments ended(String id) {
        return new Assignments(open.stream().filter(each -> !each.id().equals(id)).toList(), unattendedRuns, unattendedAssignments, taken);
    }

    /** 自动开了一轮。 */
    public Assignments ranUnattended() {
        return new Assignments(open, unattendedRuns + 1, unattendedAssignments, taken);
    }

    /** 收下了一次送达：记下编号（满了丢最早的）。 */
    public Assignments took(String delivery) {
        List<String> grown = new ArrayList<>(taken);
        grown.add(delivery);
        return new Assignments(open, unattendedRuns, unattendedAssignments,
                grown.size() > TAKEN_MAX ? grown.subList(grown.size() - TAKEN_MAX, grown.size()) : grown);
    }

    public boolean hasTaken(String delivery) {
        return taken.contains(delivery);
    }

    /** 人说话了；派活会话收下一次交代也算（那条链由交代方自己的计数限住，dispatch）。 */
    public Assignments attended() {
        return unattendedRuns == 0 && unattendedAssignments == 0 ? this : new Assignments(open, 0, 0, taken);
    }
}
