package tantalum.value.event;

import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;

/**
 * 用户给会话改名。{@code title} 已去掉首尾空白；空串表示恢复自动标题（第一句的开头）。
 * 只是展示用的名字：内核照记，不改会话和运行。
 */
public record SessionRenamed(SubmissionId submission, RequestId request, String title) implements Submitted {

    public static final String TYPE = "SessionRenamed";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
