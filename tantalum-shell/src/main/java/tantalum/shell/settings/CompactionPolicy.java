package tantalum.shell.settings;

import java.util.List;
import tantalum.shell.model.ContextWindow;
import tantalum.value.id.Principal;
import tantalum.value.state.CompactionSettings;

/**
 * 把当前窗口、用户选的百分比和配置合成一份压缩设置（compaction-threshold T4、T7）：压缩点
 * {@code T = min(floor(W × 百分比 / 100), W − R)}，R 是给回复留的预算。每次提问组装上下文时调用。
 */
public final class CompactionPolicy {

    /**
     * 配置里的压缩参数。{@code reserve} 已经是 {@code max(reserve-tokens, 该供应商的 max-tokens)}；{@code bytesPerToken}
     * 是该供应商的估算系数。
     */
    public record Limits(long reserve, long keepRecent, int maxKeptResultBytes, int maxSummarizedResultBytes,
                         long summaryMaxTokens, double bytesPerToken) {}

    /** 设置页的一个选项：百分比和对应的压缩点。 */
    public record Point(int percent, long tokens) {}

    private final ContextWindow window;
    private final UserSettings users;
    private final Limits limits;

    public CompactionPolicy(ContextWindow window, UserSettings users, Limits limits) {
        this.window = window;
        this.users = users;
        this.limits = limits;
    }

    /** 这个用户这次提问的压缩设置；窗口未知或不大于预留时为 null（不自动压缩）。 */
    public CompactionSettings settings(Principal user) {
        long tokens = window.current().tokens();
        if (tokens <= limits.reserve()) {
            return null;
        }
        return new CompactionSettings(tokens, limits.reserve(), limits.keepRecent(), limits.maxKeptResultBytes(),
                limits.maxSummarizedResultBytes(), limits.summaryMaxTokens(), point(tokens, users.compactionPercent(user)),
                limits.bytesPerToken());
    }

    /** 四个选项各自的压缩点；窗口未知时为空。 */
    public List<Point> points() {
        long tokens = window.current().tokens();
        if (tokens <= limits.reserve()) {
            return List.of();
        }
        return UserSettings.COMPACTION_PERCENTS.stream().map(percent -> new Point(percent, point(tokens, percent))).toList();
    }

    public ContextWindow.Reading window() {
        return window.current();
    }

    private long point(long tokens, int percent) {
        return Math.min(Math.floorDiv(tokens * percent, 100), tokens - limits.reserve());
    }
}
