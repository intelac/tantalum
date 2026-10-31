package tantalum.value.decision;

import java.util.List;
import java.util.Objects;
import tantalum.value.command.Command;
import tantalum.value.state.KernelState;

/** {@code Kernel.step} 的结果。 */
public record Decision(KernelState newState, List<Command> commands, List<Fact> facts) {

    public Decision {
        Objects.requireNonNull(newState, "newState");
        commands = List.copyOf(commands);
        facts = List.copyOf(facts);
    }

    public static Decision unchanged(KernelState state) {
        return new Decision(state, List.of(), List.of());
    }
}
