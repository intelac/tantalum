package tantalum.local.wire;

import java.util.Base64;
import java.util.List;
import java.util.Objects;
import tantalum.local.tool.LocalImage;
import tantalum.local.tool.LocalResult;

/** 电脑→服务器：一次调用的结果。图片的字节用 base64 放在帧里。 */
public record Result(String id, String text, boolean error, List<Image> images) implements Message {

    public Result {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        images = images == null ? List.of() : List.copyOf(images);
    }

    public static Result of(String id, LocalResult result) {
        return new Result(id, result.text(), result.error(), result.images().stream().map(Image::of).toList());
    }

    /** 还原成工具的结果；base64 不合法时抛 {@link IllegalArgumentException}。 */
    public LocalResult toLocal() {
        return new LocalResult(text, error, images.stream().map(Image::toLocal).toList());
    }

    public record Image(String mime, String base64) {

        public Image {
            Objects.requireNonNull(mime, "mime");
            Objects.requireNonNull(base64, "base64");
        }

        static Image of(LocalImage image) {
            return new Image(image.mime(), Base64.getEncoder().encodeToString(image.bytes()));
        }

        LocalImage toLocal() {
            return new LocalImage(mime, Base64.getDecoder().decode(base64));
        }
    }
}
