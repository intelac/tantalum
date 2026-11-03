package tantalum.value.attachment;

import java.util.Objects;

/**
 * 一个原样保存的文件。{@code mediaType} 是浏览器报的类型，只供显示；是不是 PDF 看 {@code pages}：
 * 上传时文件头是 {@code %PDF-} 且数得出页数才有值。
 */
public record FileRef(String id, String name, String mediaType, long bytes, Integer pages) implements AttachmentRef {

    public static final String UNKNOWN_TYPE = "application/octet-stream";

    public FileRef {
        AttachmentRef.checkId(id);
        AttachmentRef.checkName(name);
        Objects.requireNonNull(mediaType, "mediaType");
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must not be negative");
        }
        if (pages != null && pages <= 0) {
            throw new IllegalArgumentException("pages must be positive");
        }
    }

    public boolean pdf() {
        return pages != null;
    }
}
