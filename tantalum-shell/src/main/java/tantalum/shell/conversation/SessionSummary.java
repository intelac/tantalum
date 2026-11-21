package tantalum.shell.conversation;

import java.time.Instant;
import tantalum.value.id.SessionId;
import tantalum.value.state.RunState;

/**
 * 侧栏里的一行：标题取第一条提问的开头（用户改过名就用改的），最近活动取条目里最晚的时间，{@code runStatus} 没有运行时是 {@code idle}，
 * {@code dir} 是会话的工作目录，没有为 null。
 */
public record SessionSummary(SessionId id, String title, Instant lastActivity, String runStatus, String dir, boolean offRecord) {

    /** 不是“不进记忆”的会话时。 */
    public SessionSummary(SessionId id, String title, Instant lastActivity, String runStatus, String dir) {
        this(id, title, lastActivity, runStatus, dir, false);
    }

    public static final String IDLE = "idle";
    static final int TITLE_LENGTH = 40;

    public static SessionSummary of(SessionId id, RunState activeRun, Transcript.View view) {
        // 第一条提问和最大时间由对话视图随每一步记下（内存只留尾巴，live-views）
        String title = view.firstUser() == null ? "" : titleOf(view.firstUser().text());
        Instant last = view.latest();
        String status = activeRun == null ? IDLE : activeRun.phase().name();
        return new SessionSummary(id, title, last, status, null);
    }

    /** 第一行，最多 {@value #TITLE_LENGTH} 个字符，截断时加省略号。 */
    public static String titleOf(String text) {
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() <= TITLE_LENGTH ? line : line.substring(0, TITLE_LENGTH) + "…";
    }
}
