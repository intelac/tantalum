package tantalum.memory;

import java.util.List;
import java.util.function.Function;
import tantalum.memory.capture.Quotes;
import tantalum.memory.ledger.MemoryItem;
import tantalum.shell.plugin.RunSegment;
import tantalum.shell.plugin.ToolContext;

/**
 * 记忆工具写入时的信任判断（memory-final-plan 第 7 节）：这一轮读过外部内容的话，模型写的记忆要等你确认；
 * 工具带了你的原话（在你这一轮的话里找得到）就算你亲口说的，直接生效。“不进记忆”的会话里一律不写。
 */
public final class ToolTrust {

    /** 结论：来源，和要不要等你确认。 */
    public record Verdict(MemoryItem.Source source, boolean pendingConfirm, java.util.List<String> outsideTools) {}

    private final Function<ToolContext, List<RunSegment>> views;

    /** {@code views} 给这次调用所在这一轮到目前为止的分段（平台的 runView）。 */
    public ToolTrust(Function<ToolContext, List<RunSegment>> views) {
        this.views = views;
    }

    /** 不看这一轮（测试）：一律当作没读过外部内容。 */
    public static ToolTrust none() {
        return new ToolTrust(context -> List.of());
    }

    public Verdict judge(ToolContext context, String quote) {
        List<RunSegment> segments = context.run() == null ? List.of() : views.apply(context);
        MemoryItem.Source found = quote == null || quote.isBlank() ? null : Quotes.find(quote, segments);
        List<String> outside = segments.stream().filter(segment -> segment.role() == RunSegment.Role.TOOL
                && !Quotes.TRUSTED_TOOLS.contains(segment.tool()) && !MemoryTools.PLUGIN.equals(segment.plugin()))
                .map(RunSegment::tool).distinct().toList();
        if (found == MemoryItem.Source.USER_SAID) {
            return new Verdict(MemoryItem.Source.USER_SAID, false, outside);
        }
        return new Verdict(MemoryItem.Source.ASSISTANT_INFERRED, !outside.isEmpty(), outside);
    }

    /** 不进记忆的会话里给模型的话。 */
    static final String OFF_RECORD = "This conversation is off the record: nothing is kept in memory. Tell the user if they asked you to remember something.";

    /** 等确认时给模型的话。 */
    static String pendingNote(Verdict verdict) {
        return " It was saved for the user to confirm, because this turn read outside content (" + String.join(", ", verdict.outsideTools())
                + "); it takes effect once they confirm it in the console. Tell the user what you saved so they can confirm it.";
    }
}
