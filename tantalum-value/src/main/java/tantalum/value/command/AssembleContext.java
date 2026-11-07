package tantalum.value.command;

import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.model.ModelChoice;

/**
 * 组装提示词。{@code requested} 是开始这个运行的提交里带的选择（压缩后重新组装时是这个运行已经在用的），
 * {@code current} 是会话现在的选择；shell 据此定这次用哪个模型。都可以为 null。
 */
public record AssembleContext(CommandId id, RunRef run, ModelChoice requested, ModelChoice current) implements Command {

    public AssembleContext(CommandId id, RunRef run) {
        this(id, run, null, null);
    }
}
