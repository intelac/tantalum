package tantalum.local.tool;

import java.util.Objects;

/** 电脑上读到的一张图片：按文件头认出的类型和原始字节，没有规范化。 */
public record LocalImage(String mime, byte[] bytes) {

    public LocalImage {
        Objects.requireNonNull(mime, "mime");
        Objects.requireNonNull(bytes, "bytes");
    }
}
