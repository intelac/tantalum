package tantalum.value.state;

import java.util.Objects;
import tantalum.value.event.TextWithdrawn;

/**
 * 会话里撤下的一段文字（{@link TextWithdrawn}）：历史里第 {@code before} 条之前的消息，给模型看的时候原文换成替换。
 * 历史本身不改（只追加）；撤下以后才说的话（下标不小于 {@code before}）不受它影响，再说一遍的算新的。
 */
public record Withdrawal(String original, String replacement, int before) {

    public Withdrawal {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(replacement, "replacement");
        if (before < 0) {
            throw new IllegalArgumentException("before must not be negative");
        }
    }

    public TextWithdrawn.Swap swap() {
        return new TextWithdrawn.Swap(original, replacement);
    }
}
