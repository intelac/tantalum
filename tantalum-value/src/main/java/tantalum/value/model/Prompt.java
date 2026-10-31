package tantalum.value.model;

import java.util.List;
import java.util.Objects;

/** 一次模型调用的完整输入。 */
public record Prompt(String system, List<ToolSpec> tools, List<Message> messages) {

    public Prompt {
        Objects.requireNonNull(system, "system");
        tools = List.copyOf(tools);
        messages = List.copyOf(messages);
    }
}
