package tantalum.kernel;

import java.util.List;

import tantalum.value.id.RunRef;

import tantalum.value.decision.StepFailed;

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
import tantalum.value.state.AwaitingAnswer;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** 纯函数内核：查转移表、执行、捕获异常。不知道线程和 I/O 的存在。 */
public final class Kernel {

    /** 事件还没有对应运行时使用的状态名。 */
    public static final String NEW_RUN = "New";

    private final TransitionTable table;
    private final HookChain hooks;
    private final ToolSafety toolSafety;
    private final Limits limits;

    public Kernel(TransitionTable table, HookChain hooks, ToolSafety toolSafety, Limits limits) {
        this.table = table;
        this.hooks = hooks;
        this.toolSafety = toolSafety;
        this.limits = limits;
    }

    public Decision step(KernelState state, EventEnvelope envelope) {
        RunState run = findRun(state, envelope);
        Optional<StepFunction> step = findStep(run, envelope.event());
        if (step.isEmpty()) {
            return Decision.unchanged(state);
        }
        StepContext context = new StepContext(state, envelope, run, hooks, toolSafety,
                limits.maxParallelTools(), limits.maxWorkRounds());
        try {
            return step.get().apply(context);
        } catch (RuntimeException failure) {
            if (run == null) {
                // 没有运行可以结束：留一条事实，不无痕返回（review-fixes 需求 5.1）
                RunRef where = new RunRef(envelope.principal(), envelope.session(), RunId.of(envelope.session(), envelope.seq()));
                return new Decision(state, List.of(), List.of(new StepFailed(where, envelope.event().type(), failure.getClass().getSimpleName())));
            }
            return RunEnd.fail(context, failure);
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
        return table.find(run == null ? NEW_RUN : run.phase().name(), event.type());
    }

    /** 等命令结果时认在途命令；等用户回答时只认期限的定时器（waiting-state）。 */
    private static boolean isAwaited(RunState run, ResultEvent result) {
        if (run == null) {
            return false;
        }
        return switch (run.phase()) {
            case Awaiting awaiting -> awaiting.commands().contains(result.command());
            case AwaitingAnswer waiting -> result.command().equals(waiting.timer());
            default -> false;
        };
    }
}
