package tantalum.value.task;

import java.util.List;
import java.util.Objects;
import tantalum.value.id.SessionId;

/**
 * 一个会话是为哪个任务开的：放出它的会话和任务编号，建好不变。{@code agent} 是用的 agent 的名字（默认的为 null），
 * {@code instructions} 是它的提示词（没有为 null），{@code tools} 是它能用的工具名（不限为 null）；三样都在放出时定下，回放不读文件。
 */
public record TaskOrigin(SessionId parent, String task, String agent, String instructions, List<String> tools) {

    public TaskOrigin {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(task, "task");
        tools = tools == null ? null : List.copyOf(tools);
    }
}
