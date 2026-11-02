package tantalum.kernel.projection;

import tantalum.value.model.Message;

/** 告诉模型“可变内容变了”的用户消息。它不是用户写的：压缩时靠开头认出它。 */
public final class ContextUpdate {

    /** 所有上下文更新消息的开头。 */
    public static final String PREFIX = "[Context update, not written by the user.";
    private static final String HEADER = PREFIX + " These changes apply on top of what you were given earlier.]\n\n";

    private ContextUpdate() {}

    /** {@code changes} 来自 {@link ContextDiff}。 */
    public static Message message(String changes) {
        return Message.user(HEADER + changes);
    }

    public static boolean isUpdate(String text) {
        return text.startsWith(PREFIX);
    }
}
