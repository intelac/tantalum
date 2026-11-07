package tantalum.value.model;

/**
 * 不是用户写的、告诉模型“有东西变了”的消息怎么认。内核写这类消息，shell 的模型客户端决定怎么发，
 * 规则只在这里一份：
 * <ul>
 *   <li>上下文更新：一条用户消息，文字以 {@link #PREFIX} 开头（内核的 {@code ContextUpdate}）；</li>
 *   <li>系统更新：一条 {@link Role#SYSTEM} 消息，只装工具变化和钩子说明；</li>
 *   <li>换模型的说明：一条 {@link Role#SYSTEM} 消息，只装 {@link ModelChangeBlock}。</li>
 * </ul>
 */
public interface SystemUpdates {

    /** 上下文更新消息的开头，也是并进用户消息的每段说明的开头。 */
    String PREFIX = "[Context update, not written by the user.";

    /** 带后半句和空行的整段开头。 */
    String HEADER = PREFIX + " These changes apply on top of what you were given earlier.]\n\n";

    static boolean isContextUpdate(String text) {
        return text.startsWith(PREFIX);
    }

    /** 工具变化或钩子说明的系统消息；换模型的说明不算。 */
    static boolean isSystemUpdate(Message message) {
        return message.role() == Role.SYSTEM && !message.content().isEmpty()
                && message.content().stream().allMatch(block -> block instanceof ToolChangeBlock || block instanceof HookTextBlock);
    }

    static boolean isModelChange(Message message) {
        return message.role() == Role.SYSTEM && !message.content().isEmpty()
                && message.content().stream().allMatch(ModelChangeBlock.class::isInstance);
    }
}
