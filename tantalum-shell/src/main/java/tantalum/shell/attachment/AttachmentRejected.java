package tantalum.shell.attachment;

/** 附件被拒绝。{@link Reason} 决定接口返回的状态码，消息原样显示给用户。 */
public final class AttachmentRejected extends Exception {

    public enum Reason {
        /** 单个文件超过字节上限：413。 */
        TOO_LARGE,
        /** 图片的像素或边长超限：422。 */
        IMAGE_TOO_LARGE,
        /** 用户的附件配额已满：413。 */
        QUOTA_FULL,
        /** 声称是图片但不是、解不开：422。 */
        INVALID_IMAGE,
        /** 规范化排队超时：503，可以重试。 */
        BUSY
    }

    private final Reason reason;

    public AttachmentRejected(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
