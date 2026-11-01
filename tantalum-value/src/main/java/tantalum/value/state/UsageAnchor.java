package tantalum.value.state;

/**
 * 规则 1 的锚点：上次回复实测的 prompt 加输出 token 数，以及收到回复时历史的消息数。
 * 之后新增的消息按估算累加；压缩后锚点作废，等下一次回复重建。
 */
public record UsageAnchor(long tokens, int messageCount) {

    public UsageAnchor {
        if (tokens < 0 || messageCount < 0) {
            throw new IllegalArgumentException("anchor values must not be negative");
        }
    }
}
