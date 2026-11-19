package tantalum.value.state;

import java.util.Objects;
import tantalum.value.event.TextWithdrawn;

/**
 * 会话里撤下的一段文字（{@link TextWithdrawn}）：历史里第 {@code before} 条之前的消息，给模型看的时候原文换成替换。
 * 历史本身不改（只追加）；撤下以后才说的话（下标不小于 {@code before}）不受它影响，再说一遍的算新的。
 * {@code seq} 是撤下它的那条事件的序号：按序号看账的投影（比如给插件读的一轮对话）据此只换它之前的。
 */
public record Withdrawal(String original, String replacement, int before, long seq) {

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
