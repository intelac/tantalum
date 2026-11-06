package tantalum.shell.attachment;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.ImageRef;

/**
 * 附件的各项上限。图片的默认值照 DSH（{@code packages/attachment/attachment-local/src/index.ts:33-60}，需求 A4）；
 * 文件和配额是多用户服务器自定的（A5、需求 2.7）。
 *
 * @param maxImagesPerMessage 一条消息最多几张图片
 * @param maxImageBytesPerMessage 一条消息里图片的合计字节
 * @param maxImageBytes 上传的一张图片最多多少字节
 * @param maxImagePixels 一张图片最多多少像素，解码前按文件头查
 * @param maxImageEdge 一张图片单边最多多少像素
 * @param maxFilesPerMessage 一条消息最多几个文件
 * @param maxFileBytes 一个文件最多多少字节
 * @param quotaBytes 每个用户存储的附件合计字节
 * @param normalizeConcurrency 同时规范化几张图片
 */
public record AttachmentLimits(int maxImagesPerMessage, long maxImageBytesPerMessage, long maxImageBytes, long maxImagePixels,
                               int maxImageEdge, int maxFilesPerMessage, long maxFileBytes, long quotaBytes,
                               int normalizeConcurrency) {

    private static final long MIB = 1024 * 1024;

    public AttachmentLimits {
        if (maxImagesPerMessage <= 0 || maxImageBytesPerMessage <= 0 || maxImageBytes <= 0 || maxImagePixels <= 0
                || maxImageEdge <= 0 || maxFilesPerMessage <= 0 || maxFileBytes <= 0 || quotaBytes <= 0
                || normalizeConcurrency <= 0) {
            throw new IllegalArgumentException("attachment limits must be positive");
        }
    }

    public static AttachmentLimits defaults() {
        return new AttachmentLimits(20, 200 * MIB, 50 * MIB, 64_000_000, 65_535, 20, 50 * MIB, 5 * 1024 * MIB, 2);
    }

    /** 一条消息里的附件超出哪条限制（A4、A5、需求 2.1）；都没超出时为空。按服务器的元数据算，不看客户端报的。 */
    public Optional<Violation> violation(List<AttachmentRef> attachments) {
        long images = attachments.stream().filter(ImageRef.class::isInstance).count();
        long imageBytes = attachments.stream().filter(ImageRef.class::isInstance).mapToLong(AttachmentRef::bytes).sum();
        long files = attachments.size() - images;
        if (images > maxImagesPerMessage) {
            return Optional.of(new Violation(Violation.Kind.TOO_MANY_IMAGES,
                    "a message may have at most " + maxImagesPerMessage + " images; this one has " + images,
                    Map.of("max", maxImagesPerMessage, "count", images)));
        }
        if (imageBytes > maxImageBytesPerMessage) {
            return Optional.of(new Violation(Violation.Kind.IMAGES_TOO_LARGE,
                    "the images in a message may total at most " + maxImageBytesPerMessage + " bytes",
                    Map.of("limit", maxImageBytesPerMessage)));
        }
        if (files > maxFilesPerMessage) {
            return Optional.of(new Violation(Violation.Kind.TOO_MANY_FILES,
                    "a message may have at most " + maxFilesPerMessage + " files; this one has " + files,
                    Map.of("max", maxFilesPerMessage, "count", files)));
        }
        return Optional.empty();
    }

    /** 超了哪一条：英文诊断，加上控制台说给人听时要的数字（console-i18n I9）。 */
    public record Violation(Kind kind, String diagnostic, Map<String, Object> params) {
        public enum Kind { TOO_MANY_IMAGES, IMAGES_TOO_LARGE, TOO_MANY_FILES }
    }

    /** 上传时还不知道是图片还是文件，边读边查用两者中大的；知道类型后再按各自的上限查。 */
    public long maxUploadBytes() {
        return Math.max(maxImageBytes, maxFileBytes);
    }
}
