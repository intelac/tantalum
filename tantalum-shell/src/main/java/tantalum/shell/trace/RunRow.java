package tantalum.shell.trace;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import tantalum.shell.trace.TraceRecord.RunHead;
import tantalum.shell.trace.TraceRecord.UserInput;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.state.EndReason;

/**
 * 运行表的一行：一次运行是谁的、怎么开始、怎么结束、第一句提问，和整次运行的汇总。{@code end} 为空表示还在进行。
 * 实时、启动补齐和重建都用 {@link #of} 算，所以结果相同。
 */
public record RunRow(Principal principal, SessionId session, RunId run, Integer turn, RunHead.Trigger trigger, Instant start,
                     Instant end, EndReason endReason, String prompt, Tally tally) {

    static final int PROMPT_CHARS = 200;

    /** {@code head} 是这次运行的运行头，{@code records} 是它到目前为止的全部记录（含运行头）。 */
    public static RunRow of(Principal owner, SessionId session, RunHead head, Collection<TraceRecord> records) {
        RunTree tree = RunTree.of(head.head().run(), records);
        return new RunRow(owner, session, head.head().run(), head.head().turn(), head.trigger(), head.head().at(), head.head().end(),
                head.endReason(), prompt(records), tree.root().tally());
    }

    /** 人写的第一句：来源是用户、不是续写；空白合并成一个空格，截到 200 个字符。没有时为 null。 */
    static String prompt(Collection<TraceRecord> records) {
        return records.stream()
                .filter(UserInput.class::isInstance)
                .map(UserInput.class::cast)
                .filter(input -> input.text() != null && input.mode() != UserInput.Mode.CONTINUE && "user".equals(input.source().type()))
                .min(Comparator.comparing(TraceRecord::head, (a, b) -> RunTree.comparePos(a.pos(), b.pos())))
                .map(input -> {
                    String text = input.text().replaceAll("\\s+", " ").strip();
                    return text.length() <= PROMPT_CHARS ? text : text.substring(0, PROMPT_CHARS);
                })
                .orElse(null);
    }
}
