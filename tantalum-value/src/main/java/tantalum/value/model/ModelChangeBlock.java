package tantalum.value.model;

import java.util.Objects;

/**
 * 会话中途换了路线或模型（model-picker M12）：之前的回答是 {@code from} 写的，从这条消息起是 {@code to}。只出现在
 * {@link Role#SYSTEM} 消息里，紧跟这次的用户消息追加，已经发出的内容一个字不动。两个都是 {@link ModelChoice#label()}，
 * 写进 journal 时就定下。客户端把它写成这次用户消息开头的一段文字。
 */
public record ModelChangeBlock(String from, String to) implements ContentBlock {

    public ModelChangeBlock {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    /** 发给模型的文字，两种协议相同。 */
    public String text() {
        return "[Model changed: earlier assistant turns were written by " + from + "; you are now " + to + ".]";
    }
}
