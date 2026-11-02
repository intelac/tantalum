package tantalum.value.state;

import java.util.Objects;
import tantalum.value.hook.PromptPatch;

/**
 * 运行正在等摘要：为什么压缩、压缩计划、钩子追加的内容（摘要重试时要发相同的 prompt）。
 * 运行在等摘要时被取消也保留它，迟到的摘要回复据此不进历史。
 */
public record Compacting(Cause cause, Plan plan, PromptPatch patch) {

    /** 压缩是怎么触发的。接口、事实和对话条目里只出现这个名字。 */
    public enum Trigger {
        AUTO,
        OVERFLOW,
        MANUAL
    }

    /** 触发方式和它带的数据。 */
    public sealed interface Cause {
        Trigger trigger();
    }

    /** 调模型前估算超过阈值。 */
    public record Auto() implements Cause {
        @Override
        public Trigger trigger() {
            return Trigger.AUTO;
        }
    }

    /** 服务端报超长；{@code originalDetail} 是那次的原始错误，恢复失败时运行用它结束。 */
    public record Overflow(String originalDetail) implements Cause {
        @Override
        public Trigger trigger() {
            return Trigger.OVERFLOW;
        }
    }

    /** 用户要求压缩；{@code instructions} 是用户附加的要求，可为 null。 */
    public record Manual(String instructions) implements Cause {
        @Override
        public Trigger trigger() {
            return Trigger.MANUAL;
        }
    }

    public Compacting {
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(patch, "patch");
    }

    public Trigger trigger() {
        return cause.trigger();
    }
}
