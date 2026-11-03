package tantalum.shell.plugin;

import java.util.List;
import java.util.Objects;
import tantalum.value.attachment.ImageRef;

/** 工具的输出。错误也会交给模型，由模型决定下一步。{@code images} 是交给模型看的图片（如 {@code read_file} 读到图片），默认空。 */
public record ToolOutput(String text, boolean error, List<ImageRef> images) {

    public ToolOutput {
        Objects.requireNonNull(text, "text");
        images = images == null ? List.of() : List.copyOf(images);
    }

    public ToolOutput(String text, boolean error) {
        this(text, error, List.of());
    }

    public static ToolOutput ok(String text) {
        return new ToolOutput(text, false);
    }

    public static ToolOutput error(String text) {
        return new ToolOutput(text, true);
    }

    /** 带图片的结果：文字是图片的说明行。 */
    public static ToolOutput image(String text, ImageRef image) {
        return new ToolOutput(text, false, List.of(image));
    }
}
