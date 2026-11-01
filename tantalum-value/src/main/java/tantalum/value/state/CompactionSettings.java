package tantalum.value.state;

/**
 * 一个会话的压缩设置，在第一次组装上下文时快照，之后不变，回放时用同一份。
 * {@code window} 是模型的上下文窗口，{@code reserve} 是留给回复的部分，阈值是两者之差。
 */
public record CompactionSettings(long window, long reserve, long keepRecent, int maxKeptResultBytes,
                                 int maxSummarizedResultBytes, long summaryMaxTokens) {

    public CompactionSettings {
        requirePositive("window", window);
        requirePositive("reserve", reserve);
        requirePositive("keepRecent", keepRecent);
        requirePositive("maxKeptResultBytes", maxKeptResultBytes);
        requirePositive("maxSummarizedResultBytes", maxSummarizedResultBytes);
        requirePositive("summaryMaxTokens", summaryMaxTokens);
        if (reserve >= window) {
            throw new IllegalArgumentException("reserve " + reserve + " must be smaller than window " + window);
        }
    }

    /** 规则 2 的阈值 T。 */
    public long threshold() {
        return window - reserve;
    }

    /** 规则 4 的保留预算 K。 */
    public long keepBudget() {
        return Math.min(keepRecent, threshold() / 4);
    }

    private static void requirePositive(String name, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
    }
}
