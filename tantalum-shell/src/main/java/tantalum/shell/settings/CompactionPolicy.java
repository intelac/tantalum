package tantalum.shell.settings;

import java.util.List;
import tantalum.shell.route.ModelInfo;
import tantalum.value.id.Principal;
import tantalum.value.state.CompactionSettings;

/**
 * 把这次用的模型的窗口、用户选的百分比和配置合成一份压缩设置：压缩点
 * {@code T = min(floor(W × 百分比 / 100), W − R)}，W 是这个模型的窗口，R 是给回复留的预算 {@code max(reserve-tokens, 这个模型的输出上限)}。
 * 每次提问组装上下文时调用，换了模型就按新模型算。
 */
public final class CompactionPolicy {

    /** 配置里的压缩参数（{@code tantalum.compaction.*}），和模型无关的部分。 */
    public record Limits(long reserveTokens, long keepRecent, int maxKeptResultBytes, int maxSummarizedResultBytes,
                         long summaryMaxTokens) {}

    /** 设置页的一个选项：百分比和对应的压缩点。 */
    public record Point(int percent, long tokens) {}

    private final UserSettings users;
    private final Limits limits;

    public CompactionPolicy(UserSettings users, Limits limits) {
        this.users = users;
        this.limits = limits;
    }

    /** 给这个模型的回复留多少。 */
    public long reserve(ModelInfo model) {
        return Math.max(limits.reserveTokens(), model.maxTokens());
    }

    /**
     * 这个用户这次提问的压缩设置；窗口未知或不大于预留时为 null（不自动压缩）。{@code bytesPerToken} 是这条路线的估算系数。
     */
    public CompactionSettings settings(Principal user, ModelInfo model, double bytesPerToken) {
        long tokens = model.window();
        long reserve = reserve(model);
        if (tokens <= reserve) {
            return null;
        }
        return new CompactionSettings(tokens, reserve, limits.keepRecent(), limits.maxKeptResultBytes(),
                limits.maxSummarizedResultBytes(), limits.summaryMaxTokens(), point(tokens, reserve, users.compactionPercent(user)),
                bytesPerToken);
    }

    /** 四个选项在这个模型上各自的压缩点；窗口未知时为空。 */
    public List<Point> points(ModelInfo model) {
        long tokens = model.window();
        long reserve = reserve(model);
        if (tokens <= reserve) {
            return List.of();
        }
        return UserSettings.COMPACTION_PERCENTS.stream().map(percent -> new Point(percent, point(tokens, reserve, percent))).toList();
    }

    private static long point(long tokens, long reserve, int percent) {
        return Math.min(Math.floorDiv(tokens * percent, 100), tokens - reserve);
    }
}
