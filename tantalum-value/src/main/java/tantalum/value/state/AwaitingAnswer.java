package tantalum.value.state;

import java.util.Objects;
import tantalum.value.hook.HookPoint;
import tantalum.value.id.CommandId;
import tantalum.value.question.Question;

/**
 * 等用户回答（waiting-state）。没有在途命令，不计工作轮数，不超时。{@code origin} 是返回 {@code Ask} 的钩子位置，
 * {@code toolCallId} 是 {@code ask_user} 的调用 id，二者恰好一个非空；{@code timer} 是期限的定时器命令，没有期限为 null。
 */
public record AwaitingAnswer(Question question, HookPoint origin, String toolCallId, CommandId timer) implements RunPhase {

    public static final String NAME = "AwaitingAnswer";

    public AwaitingAnswer {
        Objects.requireNonNull(question, "question");
        if ((origin == null) == (toolCallId == null)) {
            throw new IllegalArgumentException("exactly one of origin and toolCallId must be set");
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    /** 是 {@code ask_user} 的问题，不是钩子的。 */
    public boolean fromTool() {
        return toolCallId != null;
    }
}
