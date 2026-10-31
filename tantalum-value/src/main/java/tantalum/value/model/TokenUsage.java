package tantalum.value.model;

/** 服务端返回的 token 用量。 */
public record TokenUsage(long input, long output, long cacheRead, long cacheWrite) {

    public TokenUsage {
        if (input < 0 || output < 0 || cacheRead < 0 || cacheWrite < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }
}
