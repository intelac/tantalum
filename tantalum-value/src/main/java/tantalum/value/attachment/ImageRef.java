package tantalum.value.attachment;

import java.util.Objects;
import java.util.Set;

/**
 * 一张规范化后的图片。{@code width}、{@code height} 是存储的尺寸；上传时缩小过的，
 * {@code sourceWidth}、{@code sourceHeight} 记原尺寸，没缩小过的都为 {@code null}。
 */
public record ImageRef(String id, String name, String mediaType, long bytes, int width, int height,
        Integer sourceWidth, Integer sourceHeight) implements AttachmentRef {

    public static final Set<String> MEDIA_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    public ImageRef {
        AttachmentRef.checkId(id);
        AttachmentRef.checkName(name);
        Objects.requireNonNull(mediaType, "mediaType");
        if (!MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalArgumentException("unsupported image type: " + mediaType);
        }
        if (bytes <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image bytes and size must be positive");
        }
        if ((sourceWidth == null) != (sourceHeight == null)) {
            throw new IllegalArgumentException("sourceWidth and sourceHeight go together");
        }
        if (sourceWidth != null && (sourceWidth < width || sourceHeight < height)) {
            throw new IllegalArgumentException("source size must not be smaller than the stored size");
        }
    }

    public ImageRef(String id, String name, String mediaType, long bytes, int width, int height) {
        this(id, name, mediaType, bytes, width, height, null, null);
    }
}
