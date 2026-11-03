package tantalum.kernel.hook;

import tantalum.kernel.compaction.Compactor;
import tantalum.value.model.Message;

/** 钩子判断消息用的规则。和压缩用同一份：插件自己写一份的话，摘要里引用到的文字也会被当成用户写的。 */
public final class HookMessages {

    private HookMessages() {}

    /** 用户亲笔的文字消息：不是工具结果、不是上下文更新、也不是摘要。 */
    public static boolean isUserText(Message message) {
        return Compactor.isUserText(message);
    }
}
