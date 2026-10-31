package tantalum.shell.plugin;

import java.util.Objects;

/** 工具的输出。错误也会交给模型，由模型决定下一步。 */
public record ToolOutput(String text, boolean error) {

    public ToolOutput {
        Objects.requireNonNull(text, "text");
    }

    public static ToolOutput ok(String text) {
        return new ToolOutput(text, false);
    }

    public static ToolOutput error(String text) {
        return new ToolOutput(text, true);
    }
}
