package tantalum.kernel;

import tantalum.value.id.Principal;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import tantalum.value.command.Command;
import tantalum.value.decision.Decision;
import tantalum.value.decision.Fact;
import tantalum.value.event.EventEnvelope;
import tantalum.value.state.KernelState;

/** 把事件序列折叠成状态。命令只被算出来，不执行，没有副作用。 */
public final class Replay {

    public record Result(KernelState state, List<Command> commands, List<Fact> facts) {}

    private Replay() {}

    public static Result run(Kernel kernel, List<EventEnvelope> events) {
        KernelState state = KernelState.empty();
        List<Command> commands = new ArrayList<>();
        List<Fact> facts = new ArrayList<>();
        Map<Principal, Long> previous = new HashMap<>();
        for (EventEnvelope envelope : events) {
            Long last = previous.put(envelope.principal(), envelope.seq());
            if (last != null && envelope.seq() <= last) {
                throw new IllegalArgumentException("event out of order at seq " + envelope.seq());
            }
            Decision decision = kernel.step(state, envelope);
            state = decision.newState();
            commands.addAll(decision.commands());
            facts.addAll(decision.facts());
        }
        return new Result(state, commands, facts);
    }
}
