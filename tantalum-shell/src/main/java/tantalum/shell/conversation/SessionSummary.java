package tantalum.shell.conversation;

import java.time.Instant;
import java.util.List;
import tantalum.shell.conversation.TranscriptItem.RunStatus;
import tantalum.shell.conversation.TranscriptItem.UserMessage;
import tantalum.value.id.SessionId;
import tantalum.value.state.RunState;

/** 侧栏里的一行：标题取第一条提问的开头，最近活动取条目里最晚的时间，{@code runStatus} 没有运行时是 {@code idle}。 */
public record SessionSummary(SessionId id, String title, Instant lastActivity, String runStatus) {

    public static final String IDLE = "idle";
    static final int TITLE_LENGTH = 40;

    public static SessionSummary of(SessionId id, RunState activeRun, List<TranscriptItem> items) {
        String title = "";
        Instant last = Instant.EPOCH;
        for (TranscriptItem item : items) {
            if (title.isEmpty() && item instanceof UserMessage message) {
                title = titleOf(message.text());
            }
            Instant at = item instanceof RunStatus status ? status.updatedAt() : item.at();
            if (at.isAfter(last)) {
                last = at;
            }
        }
        String status = activeRun == null ? IDLE : activeRun.phase().name();
        return new SessionSummary(id, title, last, status);
    }

    /** 第一行，最多 {@value #TITLE_LENGTH} 个字符，截断时加省略号。 */
    public static String titleOf(String text) {
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() <= TITLE_LENGTH ? line : line.substring(0, TITLE_LENGTH) + "…";
    }
}
