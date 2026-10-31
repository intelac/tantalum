package tantalum.value.model;

import java.util.Objects;

/** 一次工具调用的结果。{@code code} 只在合成错误时有值，如 {@code TOOL_OUTCOME_UNKNOWN}。 */
public record ToolResultBlock(String toolCallId, String text, boolean error, String code) implements ContentBlock {

    public ToolResultBlock {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(text, "text");
    }
}
