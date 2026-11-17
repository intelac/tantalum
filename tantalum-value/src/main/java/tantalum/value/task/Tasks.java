package tantalum.value.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 会话上和任务有关的一块，只由事件改。{@code open} 是放出去还没回来的任务，按放出的顺序；{@code origin} 只有为任务开的会话有；
 * {@code unattendedRuns}、{@code unattendedTasks} 是从上一次人说话以来自动开的轮数和放出的任务数，人一说话清零。
 */
public record Tasks(List<OpenTask> open, TaskOrigin origin, int unattendedRuns, int unattendedTasks) {

    public static final Tasks NONE = new Tasks(List.of(), null, 0, 0);

    public Tasks {
        open = List.copyOf(open);
    }

    public Optional<OpenTask> find(String id) {
        return open.stream().filter(task -> task.id().equals(id)).findFirst();
    }

    public Tasks started(OpenTask task) {
        List<OpenTask> grown = new ArrayList<>(open);
        grown.add(task);
        return new Tasks(grown, origin, unattendedRuns, unattendedTasks + 1);
    }

    public Tasks ended(String id) {
        return new Tasks(open.stream().filter(task -> !task.id().equals(id)).toList(), origin, unattendedRuns, unattendedTasks);
    }

    public Tasks withOrigin(TaskOrigin newOrigin) {
        return new Tasks(open, newOrigin, unattendedRuns, unattendedTasks);
    }

    /** 自动开了一轮。 */
    public Tasks ranUnattended() {
        return new Tasks(open, origin, unattendedRuns + 1, unattendedTasks);
    }

    /** 人说话了。 */
    public Tasks attended() {
        return unattendedRuns == 0 && unattendedTasks == 0 ? this : new Tasks(open, origin, 0, 0);
    }
}
