package tantalum.value.model;

import java.util.List;
import java.util.Objects;
import tantalum.value.attachment.ImageRef;

/**
 * 一次工具调用的结果。{@code code} 只在合成错误时有值，如 {@code TOOL_OUTCOME_UNKNOWN}。
 * {@code images} 是结果里交给模型的图片，默认空。
 */
public record ToolResultBlock(String toolCallId, String text, boolean error, String code, List<ImageRef> images)
        implements ContentBlock {

    public ToolResultBlock {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(text, "text");
        images = images == null ? List.of() : List.copyOf(images);
    }

    public ToolResultBlock(String toolCallId, String text, boolean error, String code) {
        this(toolCallId, text, error, code, List.of());
    }
}
