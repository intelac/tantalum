package tantalum.value.assignment;

import java.time.Instant;
import java.util.Objects;
import tantalum.value.event.Root;
import tantalum.value.id.CommandId;
import tantalum.value.id.SessionId;

/**
 * 交代出去、结果还没回来的一件事，记在交代它的会话上。{@code session} 是接这件事的会话；{@code brief} 是交代的第一行，给人和模型看；
 * 设了期限时 {@code deadline} 是期限的绝对时刻，{@code timer} 是期限定时器的命令：这个定时器到了而它还在，就按超时结束；
 * 没设期限（默认）两样都是 null。{@code root} 是交代它的那一轮追到头是谁引起的：结果回来时不能比它高。
 */
public record OpenAssignment(String id, SessionId session, String agent, String brief, Instant started, Instant deadline, CommandId timer,
                             Root root) {
    public OpenAssignment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(started, "started");
        Objects.requireNonNull(root, "root");
        if ((deadline == null) != (timer == null)) {
            throw new IllegalArgumentException("an assignment has both a deadline and its timer, or neither");
        }
    }
}
