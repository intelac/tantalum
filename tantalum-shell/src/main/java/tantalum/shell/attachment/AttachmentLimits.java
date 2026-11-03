package tantalum.shell.attachment;

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

    /** 上传时还不知道是图片还是文件，边读边查用两者中大的；知道类型后再按各自的上限查。 */
    public long maxUploadBytes() {
        return Math.max(maxImageBytes, maxFileBytes);
    }
}
