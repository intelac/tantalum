package tantalum.shell.attachment;

import java.util.Map;

/**
 * 附件被拒绝。{@link Reason} 决定接口返回的状态码和错误代码，{@link #params()} 是控制台说给人听时要的数字；
 * 消息是英文诊断，给日志和模型看（{@code ToolImages} 把它写进工具结果）。
 */
public final class AttachmentRejected extends Exception {

    public enum Reason {
        /** 单个文件超过字节上限：413，参数 {@code limit}。 */
        TOO_LARGE,
        /** 单张图片超过字节上限：413，参数 {@code limit}。 */
        IMAGE_FILE_TOO_LARGE,
        /** 图片边长超限：422，参数 {@code width}、{@code height}、{@code limit}。 */
        IMAGE_TOO_LARGE,
        /** 图片像素总数超限：422，参数同上。 */
        IMAGE_TOO_MANY_PIXELS,
        /** 用户的附件配额已满：413。 */
        QUOTA_FULL,
        /** 声称是图片但不是、解不开：422。 */
        INVALID_IMAGE,
        /** 规范化排队超时：503，可以重试。 */
        BUSY
    }

    private final Reason reason;
    private final Map<String, Object> params;

    public AttachmentRejected(Reason reason, String message) {
        this(reason, message, Map.of());
    }

    public AttachmentRejected(Reason reason, String message, Map<String, Object> params) {
        super(message);
        this.reason = reason;
        this.params = Map.copyOf(params);
    }

    public Reason reason() {
        return reason;
    }

    /** 只有数字：上限的字节数、像素、边长。 */
    public Map<String, Object> params() {
        return params;
    }
}
