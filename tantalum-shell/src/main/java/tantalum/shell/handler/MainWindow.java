package tantalum.shell.handler;

import java.nio.charset.StandardCharsets;
import java.util.List;
import tantalum.value.id.SessionId;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.CompactionSettings;

/**
 * 主窗口（memory-final-plan 第 11 节）：日常说话、交代活、拿结果的那个会话，编号以 {@code main-} 开头（控制台每天一个）。
 * 窗口没有状态、代理有状态：最近的原话按预算保留，超过就把最早的一小批移出，换成一块“对话状态”（正在谈什么、列过哪些选项、
 * “那个”“第二个”指什么）；移出去的靠记忆找回。实现上是给这种会话一个很低的压缩点（固定部分加上预算）和一个小的保留预算，
 * 压缩本身不变；“对话状态”的写法由 {@link #NOTE} 告诉模型（摘要调用用的是会话的 system）。
 */
public final class MainWindow {

    public static final String PREFIX = "main-";
    /** 默认保留的最近原话（token）：{@code tantalum.main-window.recent-tokens}。 */
    public static final long DEFAULT_TOKENS = 8000;

    static final String NOTE = """
            ## Main window
            This is the user's main window: everyday talk, handing over work, getting results. Only the latest part of the \
            conversation stays here; older turns move out in small batches. When you are asked to summarize, write it as the \
            conversation state: what is being discussed, decisions and open questions, every option or list you gave (with its \
            numbers), and what words like "that one" or "the second" refer to. Older facts come back through memory, so search \
            it when the user refers to something no longer here. Start a separate conversation or delegate for long work.""";

    private MainWindow() {}

    public static boolean is(SessionId session) {
        return session.value().startsWith(PREFIX);
    }

    /**
     * 主窗口的压缩设置：压缩点是固定部分（system 和工具定义的估算）加上 {@code tokens}，保留预算是它的一半；都不超过模型的容量。
     * 估算和内核的一样，按字节数除以系数。
     */
    static CompactionSettings settings(CompactionSettings base, String system, List<ToolSpec> tools, long tokens) {
        double perToken = base.bytesPerTokenOrLegacy();
        long fixed = (long) ((system.getBytes(StandardCharsets.UTF_8).length + tools.toString().getBytes(StandardCharsets.UTF_8).length) / perToken);
        long point = Math.max(1, Math.min(base.capacity(), fixed + tokens));
        long keep = Math.max(1, Math.min(base.keepRecent(), tokens / 2));
        return new CompactionSettings(base.window(), base.reserve(), keep, base.maxKeptResultBytes(), base.maxSummarizedResultBytes(),
                base.summaryMaxTokens(), point, base.bytesPerToken());
    }
}
