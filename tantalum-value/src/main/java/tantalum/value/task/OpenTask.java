package tantalum.value.task;

import java.time.Instant;
import java.util.Objects;
import tantalum.value.id.CommandId;
import tantalum.value.id.SessionId;

/**
 * 放出去还没回来的一个任务，记在放出它的会话上。{@code session} 是专门为它开的会话；{@code brief} 是交代的第一行，给人和模型看；
 * {@code deadline} 是期限的绝对时刻，{@code timer} 是期限定时器的命令：这个定时器到了而任务还在，就按超时结束。
 */
public record OpenTask(String id, SessionId session, String agent, String brief, Instant started, Instant deadline, CommandId timer) {

    public OpenTask {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(started, "started");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(timer, "timer");
    }
}
