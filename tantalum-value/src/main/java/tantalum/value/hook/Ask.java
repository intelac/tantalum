package tantalum.value.hook;

import java.time.Duration;
import java.util.Objects;
import tantalum.value.question.Question;

/**
 * 钩子要问用户、等回答。运行进入等人阶段，不计工作轮数、不超时；回答从 {@code onAnswer} 回来。
 * 插件的问题只有选项，不允许自由文本。{@code deadline} 是最多等多久，null 为一直等；内核用事件的时间算成到期时刻（钩子不读时钟）。
 */
public record Ask(Question question, Duration deadline) implements HookResult {

    public Ask {
        Objects.requireNonNull(question, "question");
        if (question.freeText()) {
            throw new IllegalArgumentException("a hook's question cannot allow free text");
        }
        if (question.deadline() != null) {
            throw new IllegalArgumentException("give the deadline as a Duration on Ask, the kernel sets the instant");
        }
        if (deadline != null && (deadline.isNegative() || deadline.isZero())) {
            throw new IllegalArgumentException("deadline must be positive but was " + deadline);
        }
    }

    public Ask(Question question) {
        this(question, null);
    }

    public Ask after(Duration wait) {
        return new Ask(question, wait);
    }
}
