package tantalum.shell.profile;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.SessionKind;

/**
 * 一种会话在外壳里是什么样：每种一行，“这种会话能做什么”只写在这里（内核里的那一张是 {@code InputPolicy}：收哪种输入）。
 * 别处不按会话种类判断，都来读这张表：
 *
 * <pre>
 *                    主会话               普通会话     帮手会话
 * 工具照哪种会话的    普通会话（这一步）    普通会话     帮手会话
 * 提示词多加的一段    主窗口               没有         任务
 * 压缩               只留最近一段          标准         标准
 * 你能做的操作        全部                 全部         回答它的问题、取消它那一轮、撤下文字
 * 进侧栏和会话清单    进                   进           不进
 * 列进“工作区”那段    不列                 列           不列
 * 搜得到              能                   能           不能
 * 一轮结束通知个人插件 通知                 通知         不通知
 * </pre>
 *
 * 主窗口（memory-final-plan 第 11 节）：窗口没有状态、代理有状态。最近的原话按预算保留，超过就把最早的一小批移出，换成一块“对话状态”；
 * 移出去的靠记忆找回。实现上是一个很低的压缩点和一个小的保留预算（{@link Compaction#RECENT_WINDOW}），压缩本身不变；
 * “对话状态”的写法由 {@link #MAIN_NOTE} 告诉模型（摘要调用用的是会话的 system）。
 */
public record SessionProfile(SessionKind toolsOf, String note, Compaction compaction, Set<Action> actions, boolean listed,
                             boolean inWorkspaces, boolean searchable, boolean notifiesPlugins) {

    /** 你能对一个会话做的操作。入口只查一次。 */
    public enum Action {
        /** 对它说话。 */
        PROMPT,
        /** 往它里面上传附件。 */
        UPLOAD,
        /** 改它的收件队列里你排的话。 */
        QUEUE,
        RENAME,
        /** 手动压缩。 */
        COMPACT,
        /** 回答它问的问题。 */
        ANSWER,
        /** 取消它正在跑的那一轮。 */
        CANCEL,
        /** 叫停它交代出去的事。 */
        STOP_ASSIGNMENTS,
        /** 撤下它里面的几段文字。 */
        WITHDRAW
    }

    /** 压缩用哪一档。 */
    public enum Compaction {
        /** 照模型和管理员的设置。 */
        STANDARD,
        /** 只留最近的一段原话：压缩点是固定部分加上预算，见 {@link SessionProfile#settings}。 */
        RECENT_WINDOW
    }

    /** 主窗口默认保留的最近原话（token）：{@code tantalum.main-window.recent-tokens}。 */
    public static final long DEFAULT_RECENT_TOKENS = 8000;

    public static final String MAIN_NOTE = """
            ## Main window
            This is the user's main window for everyday talk. Only the latest part of the \
            conversation stays here; older turns move out in small batches. When you are asked to summarize, write it as the \
            conversation state: what is being discussed, decisions and open questions, every option or list you gave (with its \
            numbers), and what words like "that one" or "the second" refer to. Older facts come back through memory, so search \
            it when the user refers to something no longer here.""";

    /** 给帮手会话的固定说明：它只做这一件事，交回去的只有最后一条回答。 */
    public static final String HELPER_NOTE = """
            ## This is a task
            Another agent handed you this piece of work and went on with something else. You start with nothing but the             message below: you do not see the conversation it came from, and you cannot ask the agent that sent it.             Do the work with the tools you have. Only your last message goes back, so end with the result itself:             what you found or did, where (paths, names), and anything you could not do or are unsure of.             Do not address the user: your reader is the agent that sent this.""";

    private static final Set<Action> EVERYTHING = EnumSet.allOf(Action.class);

    private static final SessionProfile MAIN = new SessionProfile(SessionKind.REGULAR, MAIN_NOTE, Compaction.RECENT_WINDOW, EVERYTHING,
            true, false, true, true);
    private static final SessionProfile REGULAR = new SessionProfile(SessionKind.REGULAR, null, Compaction.STANDARD, EVERYTHING,
            true, true, true, true);
    private static final SessionProfile HELPER = new SessionProfile(SessionKind.HELPER, HELPER_NOTE, Compaction.STANDARD,
            EnumSet.of(Action.ANSWER, Action.CANCEL, Action.WITHDRAW), false, false, false, false);

    public SessionProfile {
        actions = Set.copyOf(actions);
    }

    public static SessionProfile of(SessionKind kind) {
        return switch (kind) {
            case MAIN -> MAIN;
            case REGULAR -> REGULAR;
            case HELPER -> HELPER;
        };
    }

    /** 满足条件的那几种，写成 SQL 的 {@code IN (...)}（索引库的查询照表过滤）。 */
    public static String kindsWhere(java.util.function.Predicate<SessionProfile> test) {
        return java.util.Arrays.stream(SessionKind.values()).filter(kind -> test.test(of(kind)))
                .map(kind -> "'" + kind.name() + "'").collect(java.util.stream.Collectors.joining(", ", "(", ")"));
    }

    public boolean allows(Action action) {
        return actions.contains(action);
    }

    /**
     * 这种会话的压缩设置。{@link Compaction#RECENT_WINDOW}：压缩点是固定部分（system 和工具定义的估算）加上 {@code recentTokens}，
     * 保留预算是它的一半；都不超过模型的容量。估算和内核的一样，按字节数除以系数。
     */
    public CompactionSettings settings(CompactionSettings base, String system, List<ToolSpec> tools, long recentTokens) {
        if (compaction == Compaction.STANDARD || base == null) {
            return base;
        }
        double perToken = base.bytesPerTokenOrLegacy();
        long fixed = (long) ((system.getBytes(StandardCharsets.UTF_8).length + tools.toString().getBytes(StandardCharsets.UTF_8).length) / perToken);
        long point = Math.max(1, Math.min(base.capacity(), fixed + recentTokens));
        long keep = Math.max(1, Math.min(base.keepRecent(), recentTokens / 2));
        return new CompactionSettings(base.window(), base.reserve(), keep, base.maxKeptResultBytes(), base.maxSummarizedResultBytes(),
                base.summaryMaxTokens(), point, base.bytesPerToken());
    }
}
