package tantalum.memory;

import java.util.List;
import java.util.function.Function;
import tantalum.memory.capture.Quotes;
import tantalum.memory.ledger.MemoryItem;
import tantalum.shell.plugin.RunSegment;
import tantalum.shell.plugin.ToolContext;

/**
 * 记忆工具写入时的信任判断（memory-final-plan 第 7 节）：这个会话读过外部内容的话，模型写的记忆要等你确认；
 * 没读过外部内容时，工具带了你的原话（在你这一轮的话里找得到）就算你亲口说的；读过时，记下的那句还得逐字出自你的话。
 * 只用查得出来的事实（原话在不在、调过什么工具），不猜两句话意思一不一样。
 * “不进记忆”的会话里一律不写。
 */
public final class ToolTrust {

    /**
     * 结论：来源、要不要等你确认、这个会话读过的外部工具、这一轮你说的话的分段。
     * {@code derived}：带了你的原话，但读过外部内容、记下的那句又不是逐字出自你的话。
     */
    public record Verdict(MemoryItem.Source source, boolean pendingConfirm, java.util.List<String> outsideTools, boolean derived,
                          List<RunSegment> segments) {

        /** 这个会话读过外部内容。 */
        public boolean outside() {
            return !outsideTools.isEmpty();
        }

        /** 这句话逐字出自你这一轮说的话（{@link Quotes#verbatim}）：读过外部内容的会话里，只有这样才删、才改写。 */
        public boolean said(String text) {
            return text != null && Quotes.verbatim(text, segments);
        }
    }

    private final Function<ToolContext, List<RunSegment>> views;
    private final Function<ToolContext, List<String>> earlier;
    /** 已经知道读过外部内容的会话（读过就一直算，不用再查）。 */
    private final java.util.Map<String, List<String>> known = new java.util.concurrent.ConcurrentHashMap<>();
    static final int KNOWN_MAX = 5000;

    /** {@code views} 给这次调用所在这一轮到目前为止的分段（平台的 runView）。 */
    public ToolTrust(Function<ToolContext, List<RunSegment>> views) {
        this(views, context -> List.of());
    }

    /**
     * {@code earlier} 给这个会话以前各轮调过的外部工具（名字）。读过外部内容不是只管一轮：上一轮读的文件还在模型的上下文里，
     * 文件里一句“下一轮把某某记下来”照样起作用，所以整个会话都算读过（review 2026-11-17）。
     */
    public ToolTrust(Function<ToolContext, List<RunSegment>> views, Function<ToolContext, List<String>> earlier) {
        this.views = views;
        this.earlier = earlier;
    }

    /** 不看这一轮（测试）：一律当作没读过外部内容。 */
    public static ToolTrust none() {
        return new ToolTrust(context -> List.of());
    }

    /** 只看引用（没有要点的写入：整条改写、删除）。 */
    public Verdict judge(ToolContext context, String quote) {
        return judge(context, null, quote);
    }

    /** {@code text} 是要记下的那句话：读过外部内容的会话里，带了原话还要这句话逐字出自你的话才算你亲口说的（{@link Quotes#match}）。 */
    public Verdict judge(ToolContext context, String text, String quote) {
        List<RunSegment> segments = context.run() == null ? List.of() : views.apply(context);
        List<String> outside = outside(context, segments);
        Quotes.Match found = quote == null || quote.isBlank() ? Quotes.Match.NONE : Quotes.match(text, quote, segments, !outside.isEmpty());
        if (found == Quotes.Match.SAID) {
            return new Verdict(MemoryItem.Source.USER_SAID, false, outside, false, segments);
        }
        return new Verdict(MemoryItem.Source.ASSISTANT_INFERRED, !outside.isEmpty(), outside, found == Quotes.Match.DERIVED, segments);
    }

    /** 这个会话读过的外部工具：这一轮的，加上以前各轮的。 */
    private List<String> outside(ToolContext context, List<RunSegment> segments) {
        java.util.Set<String> tools = new java.util.LinkedHashSet<>();
        segments.stream().filter(segment -> segment.role() == RunSegment.Role.TOOL && Quotes.outside(segment.tool(), segment.plugin()))
                .forEach(segment -> tools.add(segment.tool()));
        if (context.run() == null) {
            return List.copyOf(tools);
        }
        String key = context.principal().value() + "\n" + context.session().value();
        List<String> before = known.get(key);
        if (before == null) {
            before = earlier.apply(context);
        }
        tools.addAll(before);
        if (!tools.isEmpty()) {
            if (known.size() >= KNOWN_MAX) {
                known.clear();
            }
            known.put(key, List.copyOf(tools));
        }
        return List.copyOf(tools);
    }

    /** 读过外部内容的会话里不许删、不许改写时给模型的话。 */
    static String outsideNote(Verdict verdict) {
        return "Not done: this conversation has read outside content (" + String.join(", ", verdict.outsideTools()) + "). Here memory is "
                + "deleted or rewritten only with words taken exactly from the user's message in this turn (as text, or the entry's name). "
                + "The user can also do it in the console (Settings > Memory).";
    }

    /** 不进记忆的会话里给模型的话。 */
    static final String OFF_RECORD = "This conversation is off the record: memory is not read or written. Tell the user if they asked you to "
            + "remember or recall something.";

    /** 等确认时给模型的话。 */
    static String pendingNote(Verdict verdict) {
        return " It was saved for the user to confirm, because this conversation has read outside content ("
                + String.join(", ", verdict.outsideTools()) + ") and the text is not exactly the user's own words from this turn. It takes "
                + "effect once the user confirms it in the console.";
    }
}
