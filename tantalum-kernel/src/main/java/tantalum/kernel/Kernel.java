package tantalum.kernel;

import java.util.Optional;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.step.RunEnd;
import tantalum.kernel.table.StepContext;
import tantalum.kernel.table.StepFunction;
import tantalum.kernel.table.TransitionTable;
import tantalum.kernel.tool.ToolSafety;
import tantalum.value.decision.Decision;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ResultEvent;
import tantalum.value.id.RunId;
import tantalum.value.state.Awaiting;
import tantalum.value.state.Deciding;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunPhase;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** 纯函数内核：查转移表、执行、捕获异常。不知道线程和 I/O 的存在。 */
public final class Kernel {

    /** 事件还没有对应运行时使用的状态名。 */
    public static final String NEW_RUN = "New";

    private final TransitionTable table;
    private final HookChain hooks;
    private final ToolSafety toolSafety;
    private final int maxParallelTools;

    public Kernel(TransitionTable table, HookChain hooks, ToolSafety toolSafety, int maxParallelTools) {
        this.table = table;
        this.hooks = hooks;
        this.toolSafety = toolSafety;
        this.maxParallelTools = maxParallelTools;
    }

    public Decision step(KernelState state, EventEnvelope envelope) {
        RunState run = findRun(state, envelope);
        Optional<StepFunction> step = findStep(run, envelope.event());
        if (step.isEmpty()) {
            return Decision.unchanged(state);
        }
        try {
            return step.get().apply(new StepContext(state, envelope, run, hooks, toolSafety, maxParallelTools));
        } catch (RuntimeException failure) {
            if (run == null) {
                return Decision.unchanged(state);
            }
            return RunEnd.fail(state, envelope, hooks, toolSafety, maxParallelTools, run, failure);
        }
    }

    private static RunState findRun(KernelState state, EventEnvelope envelope) {
        RunId id = envelope.run();
        if (id == null) {
            SessionState session = state.sessions().get(envelope.session());
            id = session == null ? null : session.activeRun();
        }
        return id == null ? null : state.runs().get(id);
    }

    private Optional<StepFunction> findStep(RunState run, Event event) {
        if (event instanceof ResultEvent result && !isAwaited(run, result)) {
            return table.find(TransitionTable.ANY_STATE, event.type());
        }
        return table.find(stateName(run), event.type());
    }

    private static boolean isAwaited(RunState run, ResultEvent result) {
        return run != null && run.phase() instanceof Awaiting awaiting
                && awaiting.commands().contains(result.command());
    }

    /** 状态名：等待时是 {@code resumeAt}，其余是固定状态的名字。 */
    public static String stateName(RunState run) {
        if (run == null) {
            return NEW_RUN;
        }
        RunPhase phase = run.phase();
        return switch (phase) {
            case Awaiting awaiting -> awaiting.resumeAt();
            case Deciding deciding -> "Deciding";
            case Ended ended -> "Ended";
        };
    }
}
