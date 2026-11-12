package tantalum.value.model;

/**
 * 服务端返回的 token 用量。{@code input} 只算没走缓存的部分，各供应商都规范化成这个含义。
 *
 * <p>{@code weighted} 是折算后的量：部署的人给模型配了折算系数时，四项各乘自己的系数再相加（贵的模型多算、便宜的少算）。
 * 它在模型回复进会话文件之前算好、和四项一起记下，之后改系数不影响已经记下的数。没折算、或折算出来正好等于四项之和时
 * 是 null——“没折算”只有这一种写法，所以没配系数的部署写出来的文件和以前一样，两个相等的量也不会因为写法不同而不等。
 */
public record TokenUsage(long input, long output, long cacheRead, long cacheWrite, Long weighted) {

    public TokenUsage {
        if (input < 0 || output < 0 || cacheRead < 0 || cacheWrite < 0 || (weighted != null && weighted < 0)) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        if (weighted != null && weighted == input + output + cacheRead + cacheWrite) {
            weighted = null;
        }
    }

    public TokenUsage(long input, long output, long cacheRead, long cacheWrite) {
        this(input, output, cacheRead, cacheWrite, null);
    }

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0);

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(input + other.input, output + other.output,
                cacheRead + other.cacheRead, cacheWrite + other.cacheWrite, counted() + other.counted());
    }

    /** {@code other} 必须是不大于本值的早先累计，否则结果为负而抛异常。 */
    public TokenUsage minus(TokenUsage other) {
        return new TokenUsage(input - other.input, output - other.output,
                cacheRead - other.cacheRead, cacheWrite - other.cacheWrite, counted() - other.counted());
    }

    /** 模型实际读入的 token 数，含缓存读写。计量上下文大小用它。 */
    public long promptTotal() {
        return input + cacheRead + cacheWrite;
    }

    /** 四项原始数之和。 */
    public long total() {
        return input + output + cacheRead + cacheWrite;
    }

    /** 算进今日用量和额度的量：折算过用折算的，没折算就是四项之和。 */
    public long counted() {
        return weighted != null ? weighted : total();
    }

    /** 按四个系数折算：各项乘自己的系数再相加，四舍五入。系数不能为负。 */
    public TokenUsage weighed(double input, double output, double cacheRead, double cacheWrite) {
        if (!(input >= 0 && output >= 0 && cacheRead >= 0 && cacheWrite >= 0)) {
            throw new IllegalArgumentException("weights must not be negative");
        }
        double sum = this.input * input + this.output * output + this.cacheRead * cacheRead + this.cacheWrite * cacheWrite;
        return new TokenUsage(this.input, this.output, this.cacheRead, this.cacheWrite, Math.round(sum));
    }
}
