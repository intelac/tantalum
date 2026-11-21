package tantalum.value.command;

import java.util.Objects;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

/**
 * 叫停一次交代：处理器给接它的会话 {@code session} 发一条 {@code AssignmentCancelled}。
 * {@code run} 是发出这条命令的会话最近的一轮。
 */
public record StopAssignment(CommandId id, RunRef run, String assignment, SessionId session) implements Command {
    public StopAssignment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(session, "session");
    }
}
