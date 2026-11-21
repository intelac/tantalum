package tantalum.value.state;

import tantalum.value.id.SessionId;

import java.util.Objects;
import tantalum.value.event.Root;

/**
 * 一轮运行是被什么引起的。{@code startedBy} 是开这一轮的那种输入；{@code root} 是追到头谁引起的，取这一轮吃进去的所有输入里最低的，
 * 只降不升；{@code owes} 是这一轮欠着结果的那次交代的编号，没有为 null。一轮结束时（{@code RunEnd.finish}，唯一的出口）
 * 按 {@code owes} 交回恰好一个结果。
 */
// owedTo：这一轮欠的结果交给谁（为交代开的一轮从交代的 issuer 来；老的行为空时退回帮手的出身）。since：开轮时历史从头数的条数，可空（dispatch）。
public record RunCause(StartedBy startedBy, Root root, String owes, SessionId owedTo, Integer since) {

    /** 开一轮的几种来由。 */
    public enum StartedBy {
        /** 你说的话。 */
        PROMPT,
        /** 另一个会话的交代。 */
        ASSIGNMENT,
        /** 交代出去的事有了结果。 */
        REPORT,
        /** 你要的手动压缩。 */
        COMPACTION
    }

    public RunCause {
        Objects.requireNonNull(startedBy, "startedBy");
        Objects.requireNonNull(root, "root");
        if ((startedBy == StartedBy.ASSIGNMENT) != (owes != null)) {
            throw new IllegalArgumentException("exactly a run started by an assignment owes a report");
        }
    }

    /** 你说的话开的一轮（没记开始位置：老的行、测试）。 */
    public static RunCause prompt() {
        return new RunCause(StartedBy.PROMPT, Root.USER, null, null, null);
    }

    /** 开轮时记下历史从头数有几条（这一轮的回答从这里往后取，dispatch）。 */
    public RunCause startingAt(int historySize) {
        return new RunCause(startedBy, root, owes, owedTo, historySize);
    }

    /** 这一轮又吃进一条输入：{@code root} 降到两者里低的那个。 */
    public RunCause absorbing(Root other) {
        Root lowered = Root.lower(root, other);
        return lowered == root ? this : new RunCause(startedBy, lowered, owes, owedTo, since);
    }

    /** 有没有人在等这一轮：只有你说的话开的那一轮算。 */
    public boolean attended() {
        return startedBy == StartedBy.PROMPT;
    }
}
