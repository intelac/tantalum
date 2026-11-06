package tantalum.value.state;

import java.util.List;
import java.util.Objects;
import tantalum.value.hook.HookPoint;
import tantalum.value.id.RunRef;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;

/** 一次运行的状态。system 和工具只存在会话的 {@code SessionContext} 里，这里不复制；
 * {@code workOrigin} 是发起当前工作的钩子位置；{@code compacting} 在等摘要时有值；
 * {@code autoCompactionFailed} 为真后本运行不再自动压缩；{@code overflowRecovered} 为真后再超长就直接失败；
 * {@code workRounds} 是当前等待点连续做了几轮插件工作，{@code endWorkRounds} 是本运行在结束前一共做了几轮，
 * 两者都由事件算出，回放得到同样的值。
 * {@code model} 是这个运行用的模型，第一次组装上下文时定下，整个运行不变（model-picker M8）；本 spec 之前的运行为 null。
 * {@code modelBefore} 是这个运行换掉的上一个模型：换到窗口更小的模型时，第一次调用前的压缩摘要用它写，压缩失败时会话退回它；
 * 第一次正常回复后清掉（M10）。 */
public record RunState(RunRef ref, OwnerRef owner, RunPhase phase, ToolRound tools, List<Message> pendingSteers, int retries,
                       HookPoint workOrigin, Compacting compacting, boolean autoCompactionFailed, boolean overflowRecovered,
                       int workRounds, int endWorkRounds, ModelChoice model, ModelChoice modelBefore) {

    public RunState {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(phase, "phase");
        pendingSteers = List.copyOf(pendingSteers);
    }

    public static RunState start(RunRef ref, OwnerRef owner) {
        return new RunState(ref, owner, new Deciding(), null, List.of(), 0, null, null, false, false, 0, 0, null, null);
    }

    public RunState withPhase(RunPhase newPhase) {
        return new RunState(ref, owner, newPhase, tools, pendingSteers, retries, workOrigin,
                compacting, autoCompactionFailed, overflowRecovered, workRounds, endWorkRounds, model, modelBefore);
    }

    public RunState withTools(ToolRound newTools) {
        return new RunState(ref, owner, phase, newTools, pendingSteers, retries, workOrigin,
                compacting, autoCompactionFailed, overflowRecovered, workRounds, endWorkRounds, model, modelBefore);
    }

    public RunState withPendingSteers(List<Message> newSteers) {
        return new RunState(ref, owner, phase, tools, newSteers, retries, workOrigin,
                compacting, autoCompactionFailed, overflowRecovered, workRounds, endWorkRounds, model, modelBefore);
    }

    public RunState withRetries(int newRetries) {
        return new RunState(ref, owner, phase, tools, pendingSteers, newRetries, workOrigin,
                compacting, autoCompactionFailed, overflowRecovered, workRounds, endWorkRounds, model, modelBefore);
    }

    public RunState withWorkOrigin(HookPoint newWorkOrigin) {
        return new RunState(ref, owner, phase, tools, pendingSteers, retries, newWorkOrigin,
                compacting, autoCompactionFailed, overflowRecovered, workRounds, endWorkRounds, model, modelBefore);
    }

    public RunState withCompacting(Compacting newCompacting) {
        return new RunState(ref, owner, phase, tools, pendingSteers, retries, workOrigin,
                newCompacting, autoCompactionFailed, overflowRecovered, workRounds, endWorkRounds, model, modelBefore);
    }

    public RunState withAutoCompactionFailed(boolean failed) {
        return new RunState(ref, owner, phase, tools, pendingSteers, retries, workOrigin,
                compacting, failed, overflowRecovered, workRounds, endWorkRounds, model, modelBefore);
    }

    public RunState withOverflowRecovered(boolean recovered) {
        return new RunState(ref, owner, phase, tools, pendingSteers, retries, workOrigin,
                compacting, autoCompactionFailed, recovered, workRounds, endWorkRounds, model, modelBefore);
    }

    public RunState withWorkRounds(int rounds) {
        return new RunState(ref, owner, phase, tools, pendingSteers, retries, workOrigin,
                compacting, autoCompactionFailed, overflowRecovered, rounds, endWorkRounds, model, modelBefore);
    }

    public RunState withEndWorkRounds(int rounds) {
        return new RunState(ref, owner, phase, tools, pendingSteers, retries, workOrigin,
                compacting, autoCompactionFailed, overflowRecovered, workRounds, rounds, model, modelBefore);
    }

    /** 定下这个运行的模型，和它换掉的上一个（没换为 null）。 */
    public RunState withModel(ModelChoice newModel, ModelChoice before) {
        return new RunState(ref, owner, phase, tools, pendingSteers, retries, workOrigin,
                compacting, autoCompactionFailed, overflowRecovered, workRounds, endWorkRounds, newModel, before);
    }

    public boolean isEnded() {
        return phase instanceof Ended;
    }
}
