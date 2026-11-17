package tantalum.value.command;

import java.util.Objects;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

/** 停掉一个任务：处理器给它的会话 {@code session} 发一条 {@code Cancel}。{@code run} 是发出这条命令的会话最近的一轮。 */
public record StopTask(CommandId id, RunRef run, String task, SessionId session) implements Command {

    public StopTask {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(session, "session");
    }
}
