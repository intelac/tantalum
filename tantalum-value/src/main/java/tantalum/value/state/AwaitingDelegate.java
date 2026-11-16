package tantalum.value.state;

import java.time.Instant;
import tantalum.value.id.CommandId;

/**
 * 等委派交回（kernel-delegation）。和 {@link AwaitingAnswer} 同一种形状：没有在途命令，重启时不被打断。在等哪些委派看工具槽位
 * （状态是 {@code DELEGATED} 的那些）。{@code deadline} 是期限的绝对时刻，{@code timer} 是期限的定时器命令；没有期限时都为 null。
 */
public record AwaitingDelegate(Instant deadline, CommandId timer) implements RunPhase {

    public static final String NAME = "AwaitingDelegate";

    @Override
    public String name() {
        return NAME;
    }
}
