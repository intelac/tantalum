package tantalum.value.state;

import java.time.Instant;
import tantalum.value.id.CommandId;

/**
 * 等会话回答。和 {@link AwaitingAnswer} 是一对：那个等用户，这个等另一个会话；都没有在途命令，重启时不被打断。
 * 在等哪几句话看工具槽位（状态是 {@code ASKED} 的那些，{@code command} 是那句话的编号）。
 * {@code deadline} 是期限的绝对时刻，{@code timer} 是期限的定时器命令。
 */
public record AwaitingReply(Instant deadline, CommandId timer) implements RunPhase {

    public static final String NAME = "AwaitingReply";

    @Override
    public String name() {
        return NAME;
    }
}
