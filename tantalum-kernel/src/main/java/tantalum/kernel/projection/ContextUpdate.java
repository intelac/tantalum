package tantalum.kernel.projection;

import tantalum.value.model.Message;
import tantalum.value.model.SystemUpdates;

/** 告诉模型“可变内容变了”的用户消息。它不是用户写的：压缩时靠开头认出它。 */
public final class ContextUpdate {

    /** 所有上下文更新消息的开头；字面量在 value 里只有一份（review-fixes 需求 12.1）。 */
    public static final String PREFIX = SystemUpdates.PREFIX;

    private ContextUpdate() {}

    /** {@code changes} 来自 {@link ContextDiff}。 */
    public static Message message(String changes) {
        return Message.user(SystemUpdates.HEADER + changes);
    }

    public static boolean isUpdate(String text) {
        return SystemUpdates.isContextUpdate(text);
    }
}
