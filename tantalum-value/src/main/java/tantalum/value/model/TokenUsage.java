package tantalum.value.model;

/** 服务端返回的 token 用量。{@code input} 只算没走缓存的部分，各供应商都规范化成这个含义。 */
public record TokenUsage(long input, long output, long cacheRead, long cacheWrite) {

    public TokenUsage {
        if (input < 0 || output < 0 || cacheRead < 0 || cacheWrite < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0);

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(input + other.input, output + other.output,
                cacheRead + other.cacheRead, cacheWrite + other.cacheWrite);
    }

    /** {@code other} 必须是不大于本值的早先累计，否则结果为负而抛异常。 */
    public TokenUsage minus(TokenUsage other) {
        return new TokenUsage(input - other.input, output - other.output,
                cacheRead - other.cacheRead, cacheWrite - other.cacheWrite);
    }

    /** 模型实际读入的 token 数，含缓存读写。计量上下文大小和额度都用它。 */
    public long promptTotal() {
        return input + cacheRead + cacheWrite;
    }
}
