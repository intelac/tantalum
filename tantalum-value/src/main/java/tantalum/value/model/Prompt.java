package tantalum.value.model;

import java.util.List;
import java.util.Objects;

/** 一次模型调用的完整输入。{@code output} 约束这次回复：摘要调用禁止工具并限制长度，普通调用用默认值。 */
public record Prompt(String system, List<ToolSpec> tools, List<Message> messages, Output output) {

    /** {@code toolsAllowed} 为 false 时工具定义仍发送（历史里有调用），只是不许再调；{@code maxTokens} 为 0 表示用客户端配置。 */
    public record Output(boolean toolsAllowed, long maxTokens) {

        public static final Output DEFAULT = new Output(true, 0);

        public Output {
            if (maxTokens < 0) {
                throw new IllegalArgumentException("maxTokens must not be negative");
            }
        }
    }

    public Prompt {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(output, "output");
        tools = List.copyOf(tools);
        messages = List.copyOf(messages);
    }

    public Prompt(String system, List<ToolSpec> tools, List<Message> messages) {
        this(system, tools, messages, Output.DEFAULT);
    }
}
