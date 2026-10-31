package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.List;
import tantalum.kernel.Kernel;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.table.StepContext;
import tantalum.kernel.tool.ToolSafety;
import tantalum.value.command.Command;
import tantalum.value.decision.Decision;
import tantalum.value.decision.Fact;
import tantalum.value.decision.PhaseChanged;
import tantalum.value.event.EventEnvelope;
import tantalum.value.hook.HookContext;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.model.Message;
import tantalum.value.state.Awaiting;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunPhase;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** 一次 step 内累积的结果。只活在一次纯函数调用里，不会逃逸到外面。 */
final class Outcome {

    final EventEnvelope envelope;
    final HookChain hooks;
    final ToolSafety toolSafety;
    final int maxParallelTools;
    KernelState state;
    private final List<Command> commands = new ArrayList<>();
    private final List<Fact> facts = new ArrayList<>();
    private int nextCommand;

    Outcome(StepContext context) {
        this(context.state(), context.envelope(), context.hooks(), context.toolSafety(), context.maxParallelTools());
    }

    Outcome(KernelState state, EventEnvelope envelope, HookChain hooks, ToolSafety toolSafety, int maxParallelTools) {
        this.state = state;
        this.envelope = envelope;
        this.hooks = hooks;
        this.toolSafety = toolSafety;
        this.maxParallelTools = maxParallelTools;
    }

    CommandId nextCommandId() {
        return CommandIds.of(envelope.seq(), nextCommand++);
    }

    void emit(Command command) {
        commands.add(command);
    }

    void report(List<Fact> reported) {
        facts.addAll(reported);
    }

    void put(RunState run) {
        state = state.with(run);
    }

    /** 让运行进入新状态，状态名变化时产生事实。 */
    RunState enter(RunState run, RunPhase phase) {
        RunState next = run.withPhase(phase);
        put(next);
        String name = Kernel.stateName(next);
        if (!name.equals(Kernel.stateName(run))) {
            facts.add(new PhaseChanged(next.ref(), name));
        }
        return next;
    }

    RunState await(RunState run, String resumeAt, java.util.Set<CommandId> awaited) {
        return enter(run, new Awaiting(awaited, resumeAt));
    }

    SessionState session(RunRef ref) {
        return state.sessions().get(ref.session());
    }

    void append(RunRef ref, Message message) {
        state = state.with(session(ref).append(message));
    }

    HookContext hookContext(RunRef ref) {
        return HookContext.of(ref, envelope.at());
    }

    Decision decision() {
        return new Decision(state, commands, facts);
    }
}
