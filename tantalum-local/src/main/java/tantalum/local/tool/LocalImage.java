package tantalum.local.tool;

import java.util.Objects;

/**
 * 电脑上读到的一张图片：按文件头认出的类型和原始字节，没有规范化。{@code name} 是存成附件时建议的文件名（浏览器截图用），
 * 没有时为 null，服务器按调用的路径起名。
 */
public record LocalImage(String mime, byte[] bytes, String name) {

    public LocalImage(String mime, byte[] bytes) {
        this(mime, bytes, null);
    }

    public LocalImage {
        Objects.requireNonNull(mime, "mime");
        Objects.requireNonNull(bytes, "bytes");
    }
}
