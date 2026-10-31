package tantalum.value.state;

import java.util.Objects;
import java.util.Set;
import tantalum.value.id.CommandId;

/** 已发命令，等待结果。{@code resumeAt} 是已注册的步骤名，如 {@code AwaitingModel}。 */
public record Awaiting(Set<CommandId> commands, String resumeAt) implements RunPhase {

    public Awaiting {
        commands = Set.copyOf(commands);
        Objects.requireNonNull(resumeAt, "resumeAt");
    }
}
