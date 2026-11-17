package tantalum.value.event;

import java.util.List;
import java.util.Objects;
import tantalum.value.id.SessionId;
import tantalum.value.model.ModelChoice;

/**
 * 一个任务到了为它开的会话，是这个会话的创建事件。不是人说的话：只由 {@code StartTask} 的处理器放进来。
 * {@code parent} 是放出它的会话，{@code prompt} 是交代的全部内容。{@code agent}、{@code instructions}、{@code tools}、{@code model}
 * 是放出时读到的 agent 定义（默认的 agent 这几样都是 null）；{@code dir}、{@code offRecord} 照放出它的会话。
 */
public record TaskAssigned(String task, SessionId parent, String prompt, String agent, String instructions, List<String> tools,
                           ModelChoice model, String dir, boolean offRecord) implements Event {

    public static final String TYPE = "TaskAssigned";

    public TaskAssigned {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(prompt, "prompt");
        tools = tools == null ? null : List.copyOf(tools);
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
