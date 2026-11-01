package tantalum.value.state;

import java.util.Objects;
import tantalum.value.hook.PromptPatch;

/**
 * 运行正在等摘要。{@code manualInstructions} 只在手动压缩时有值；{@code originalDetail} 是超长恢复时模型的原始错误，
 * 恢复失败时运行用它结束；{@code patch} 是钩子追加的内容，摘要重试时要发相同的 prompt。
 */
public record Compacting(Trigger trigger, Plan plan, String manualInstructions, String originalDetail, PromptPatch patch) {

    /** 压缩是怎么触发的。 */
    public enum Trigger {
        AUTO,
        OVERFLOW,
        MANUAL
    }

    public Compacting {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(patch, "patch");
    }

    public Compacting(Trigger trigger, Plan plan, String manualInstructions, String originalDetail) {
        this(trigger, plan, manualInstructions, originalDetail, PromptPatch.NONE);
    }
}
