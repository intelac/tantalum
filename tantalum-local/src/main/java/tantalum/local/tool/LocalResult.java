package tantalum.local.tool;

import java.util.List;
import java.util.Objects;

/** 工具的输出。错误也会交给模型，由模型决定下一步。{@code images} 是读到的图片，原样送回服务器，由服务器规范化后交给模型。 */
public record LocalResult(String text, boolean error, List<LocalImage> images) {

    public LocalResult {
        Objects.requireNonNull(text, "text");
        images = images == null ? List.of() : List.copyOf(images);
    }

    public static LocalResult ok(String text) {
        return new LocalResult(text, false, List.of());
    }

    public static LocalResult error(String text) {
        return new LocalResult(text, true, List.of());
    }

    /** 带图片的结果：文字是图片的说明行。 */
    public static LocalResult image(String text, LocalImage image) {
        return new LocalResult(text, false, List.of(image));
    }
}
