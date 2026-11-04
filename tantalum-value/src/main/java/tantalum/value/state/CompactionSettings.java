package tantalum.value.state;

/**
 * 一次提问的压缩设置，组装上下文时由 shell 算好，随 {@code ContextAssembled} 写进 journal，回放时用同一份。
 * {@code window} 是模型的上下文窗口，{@code reserve} 是留给回复的部分。{@code point} 是用户选的压缩点
 * （compaction-threshold T4），{@code bytesPerToken} 是估算系数（T7）。这两个字段是后加的：旧事件里没有，解码为 0，
 * 阈值照旧是 {@code window − reserve}，系数照旧是 3。
 */
public record CompactionSettings(long window, long reserve, long keepRecent, int maxKeptResultBytes,
                                 int maxSummarizedResultBytes, long summaryMaxTokens, long point, double bytesPerToken) {

    /** 旧规则的估算系数：UTF-8 字节数除以 3。 */
    public static final double LEGACY_BYTES_PER_TOKEN = 3;

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
        if (point < 0 || point > window - reserve) {
            throw new IllegalArgumentException("point " + point + " must be within (0, " + (window - reserve) + "]");
        }
        if (!(bytesPerToken >= 0) || Double.isInfinite(bytesPerToken)) {
            throw new IllegalArgumentException("bytesPerToken must be positive but was " + bytesPerToken);
        }
    }

    /** 旧规则的设置：没有用户选的压缩点，按字节数除以 3 估算。 */
    public CompactionSettings(long window, long reserve, long keepRecent, int maxKeptResultBytes,
                              int maxSummarizedResultBytes, long summaryMaxTokens) {
        this(window, reserve, keepRecent, maxKeptResultBytes, maxSummarizedResultBytes, summaryMaxTokens, 0, 0);
    }

    /** 规则 2 的阈值 T：用户选的压缩点；旧事件没有时是窗口减预留。 */
    public long threshold() {
        return point > 0 ? point : window - reserve;
    }

    /** 模型一次能收多少：窗口减预留。摘要调用的头部按它截，不按用户选的压缩点。 */
    public long capacity() {
        return window - reserve;
    }

    /** 规则 4 的保留预算 K。 */
    public long keepBudget() {
        return Math.min(keepRecent, threshold() / 4);
    }

    /** 估算用的系数；旧事件没有时是 3。 */
    public double bytesPerTokenOrLegacy() {
        return bytesPerToken > 0 ? bytesPerToken : LEGACY_BYTES_PER_TOKEN;
    }

    private static void requirePositive(String name, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
    }
}
