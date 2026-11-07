package tantalum.value.model;

import java.util.Objects;

/**
 * 钩子在模型调用前追加的说明：只出现在 {@link Role#SYSTEM} 消息里，发给模型的只有 {@code text}，
 * {@code hook} 给轨迹页标来源。会话的 system 从此一个字不变，钩子的说明都走这里；发出去的说明永远留在历史里。
 */
public record HookTextBlock(String hook, String text) implements ContentBlock {

    public HookTextBlock {
        Objects.requireNonNull(hook, "hook");
        Objects.requireNonNull(text, "text");
        if (hook.isEmpty() || text.isEmpty()) {
            throw new IllegalArgumentException("a hook text names its hook and says something");
        }
    }
}
