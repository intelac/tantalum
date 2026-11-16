package tantalum.shell.handler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import tantalum.shell.conversation.SessionSummary;
import tantalum.shell.project.Project;
import tantalum.shell.project.ProjectPaths;
import tantalum.value.id.SessionId;

/**
 * 上下文里的“工作区”一段：让模型知道有哪些工作区、每个下面最近有哪些会话，好决定把活交给哪个会话、在哪个工作区另开一个、
 * 还是先新建工作区。工作区是用户电脑上的一个目录加一个名字；会话在第一句话时定下目录，之后不变，所以一个会话只属于一个工作区。
 * 这里只排版，数据由管线给。一个工作区也没有时照样有这一段（写明还没有），模型才知道可以新建。
 *
 * <p>这一段是按行比较的（{@code ContextSection.byLine}）：变了只把变的那几行告诉模型，所以每一行自己说得清（会话那行写着它在哪个
 * 工作区），而且写得尽量不变：时间只分“一天内、几天前、几周前”，上下文长度取整到一万 token，不写谁正在跑。
 */
public final class WorkspaceSection {

    public static final String NAME = "workspaces";
    static final int MAX_WORKSPACES = 12;
    static final int MAX_SESSIONS = 5;
    /** 上下文超过这么多 token 的会话标出来：再往里交活不如另开一个。 */
    static final long LARGE = 100_000;
    private static final long STEP = 10_000;

    /** 一个会话在这段里的样子；{@code tokens} 是它上下文现在大约多少 token（不知道为空：会话没载入）。 */
    public record Row(SessionSummary summary, OptionalLong tokens) {}

    private WorkspaceSection() {}

    /**
     * @param current 这一段所在的会话（列表里标出来）
     * @param rows    这个用户的会话，不含“不进记忆”的、归档的
     * @param now     现在（算“多久以前”）；为 null 时不写时间
     */
    public static String text(List<Project> workspaces, List<Row> rows, SessionId current, Instant now) {
        StringBuilder text = new StringBuilder("Workspaces are folders on the user's computer. A conversation works in one folder, fixed by its "
                + "first message.\n");
        List<Project> shown = workspaces.subList(0, Math.min(MAX_WORKSPACES, workspaces.size()));
        for (Project workspace : shown) {
            text.append("- workspace \"").append(workspace.title()).append("\" — ").append(workspace.path()).append('\n');
        }
        if (workspaces.isEmpty()) {
            text.append("- (no workspaces yet)\n");
        }
        if (workspaces.size() > MAX_WORKSPACES) {
            text.append("- (").append(workspaces.size() - MAX_WORKSPACES).append(" more workspaces not shown)\n");
        }
        StringBuilder sessions = new StringBuilder();
        List<Row> left = new ArrayList<>(rows);
        for (Project workspace : shown) {
            String key = ProjectPaths.key(workspace.path());
            List<Row> here = left.stream().filter(row -> row.summary().dir() != null && ProjectPaths.key(row.summary().dir()).equals(key)).toList();
            left.removeAll(here);
            sessions(sessions, here, "in \"" + workspace.title() + "\"", current, now);
        }
        sessions(sessions, left, "in no workspace", current, now);
        if (!sessions.isEmpty()) {
            text.append("Recent conversations:\n").append(sessions);
        }
        text.append("To hand work to a listed conversation: delegate with session=<id>. To open a new conversation in a workspace: delegate "
                + "with workspace=<its path>. Prefer a conversation already on the topic unless its context is large; then open a new "
                + "one in the same workspace and put what it needs in brief. When the work is in a folder that is not a workspace and "
                + "the user means to keep working there, add it first with workspace_add (the user is asked to confirm).");
        return text.toString();
    }

    private static void sessions(StringBuilder text, List<Row> rows, String where, SessionId current, Instant now) {
        // 按最近活动选出几个，再按编号排：谁刚说过话不改变行的先后
        List<Row> recent = rows.stream().sorted(Comparator.comparing((Row row) -> row.summary().lastActivity()).reversed()).limit(MAX_SESSIONS)
                .sorted(Comparator.comparing((Row row) -> row.summary().id().value())).toList();
        for (Row row : recent) {
            SessionSummary summary = row.summary();
            if (summary.id().equals(current)) {
                // 自己这一行不写编号、标题和时间：用不着把活交给自己，第一句话之后标题才有，写了每个会话都要多变一次
                text.append("- this conversation · ").append(where);
            } else {
                String title = summary.title() == null || summary.title().isBlank() ? "(untitled)" : summary.title().replaceAll("\\s+", " ");
                text.append("- ").append(summary.id().value()).append(" \"").append(title).append("\" · ").append(where);
                if (now != null) {
                    text.append(" · ").append(ago(Duration.between(summary.lastActivity(), now)));
                }
            }
            if (row.tokens().isPresent() && row.tokens().getAsLong() >= STEP) {
                long tokens = row.tokens().getAsLong() / STEP * STEP;
                text.append(" · ~").append(tokens / 1000).append("k tokens in context").append(tokens >= LARGE ? " (large)" : "");
            }
            text.append('\n');
        }
        if (rows.size() > recent.size()) {
            text.append("- (").append(rows.size() - recent.size()).append(" older conversations ").append(where).append(" not shown)\n");
        }
    }

    static String ago(Duration since) {
        long days = Math.max(0, since.toDays());
        if (days < 1) {
            return "active in the last day";
        }
        return days < 14 ? days + " d ago" : days / 7 + " w ago";
    }
}
