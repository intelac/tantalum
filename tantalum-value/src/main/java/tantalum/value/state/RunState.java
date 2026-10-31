package tantalum.value.state;

import java.util.List;
import java.util.Objects;
import tantalum.value.id.RunRef;
import tantalum.value.model.Message;
import tantalum.value.model.ToolSpec;

/** 一次运行的状态。{@code system} 和 {@code toolSpecs} 来自 {@code ContextAssembled}，组装前为空；
 * {@code workOrigin} 是发起当前工作的钩子位置。 */
public record RunState(RunRef ref, OwnerRef owner, RunPhase phase, String system, List<ToolSpec> toolSpecs,
                       ToolRound tools, List<Message> pendingSteers, int retries, boolean pendingRecovery,
                       String workOrigin) {

    public RunState {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(phase, "phase");
        toolSpecs = List.copyOf(toolSpecs);
        pendingSteers = List.copyOf(pendingSteers);
    }

    public static RunState start(RunRef ref, OwnerRef owner) {
        return new RunState(ref, owner, new Deciding(), null, List.of(), null, List.of(), 0, false, null);
    }

    public RunState withPhase(RunPhase newPhase) {
        return new RunState(ref, owner, newPhase, system, toolSpecs, tools, pendingSteers, retries, pendingRecovery, workOrigin);
    }

    public RunState withContext(String newSystem, List<ToolSpec> newToolSpecs) {
        return new RunState(ref, owner, phase, newSystem, newToolSpecs, tools, pendingSteers, retries, pendingRecovery, workOrigin);
    }

    public RunState withTools(ToolRound newTools) {
        return new RunState(ref, owner, phase, system, toolSpecs, newTools, pendingSteers, retries, pendingRecovery, workOrigin);
    }

    public RunState withPendingSteers(List<Message> newSteers) {
        return new RunState(ref, owner, phase, system, toolSpecs, tools, newSteers, retries, pendingRecovery, workOrigin);
    }

    public RunState withRetries(int newRetries) {
        return new RunState(ref, owner, phase, system, toolSpecs, tools, pendingSteers, newRetries, pendingRecovery, workOrigin);
    }

    public RunState withPendingRecovery(boolean newPendingRecovery) {
        return new RunState(ref, owner, phase, system, toolSpecs, tools, pendingSteers, retries, newPendingRecovery, workOrigin);
    }

    public RunState withWorkOrigin(String newWorkOrigin) {
        return new RunState(ref, owner, phase, system, toolSpecs, tools, pendingSteers, retries, pendingRecovery,
                newWorkOrigin);
    }

    public boolean ended() {
        return phase instanceof Ended;
    }
}
