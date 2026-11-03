package tantalum.value.state;

import java.util.List;
import java.util.Objects;
import tantalum.value.hook.HookPoint;
import tantalum.value.id.RunRef;
import tantalum.value.model.Message;

/** 一次运行的状态。system 和工具只存在会话的 {@code SessionContext} 里，这里不复制；
 * {@code workOrigin} 是发起当前工作的钩子位置；{@code compacting} 在等摘要时有值；
 * {@code autoCompactionFailed} 为真后本运行不再自动压缩；{@code overflowRecovered} 为真后再超长就直接失败；
 * {@code workRounds} 是当前等待点连续做了几轮插件工作，{@code endWorkRounds} 是本运行在结束前一共做了几轮，
 * 两者都由事件算出，回放得到同样的值。 */
public record RunState(RunRef ref, OwnerRef owner, RunPhase phase, ToolRound tools, List<Message> pendingSteers, int retries,
                       HookPoint workOrigin, Compacting compacting, boolean autoCompactionFailed, boolean overflowRecovered,
                       int workRounds, int endWorkRounds) {

    public RunState {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(phase, "phase");
        pendingSteers = List.copyOf(pendingSteers);
    }

    public static RunState start(RunRef ref, OwnerRef owner) {
        return new RunState(ref, owner, new Deciding(), null, List.of(), 0, null, null, false, false, 0, 0);
    }

    public RunState withPhase(RunPhase newPhase) {
        return new RunState(ref, owner, newPhase, tools, pendingSteers, retries, workOrigin,
                compacting, autoCompactionFailed, overflowRecovered, workRounds, endWorkRounds);
    }

    public RunState withTools(ToolRound newTools) {
        return new RunState(ref, owner, phase, newTools, pendingSteers, retries, workOrigin,
                compacting, autoCompactionFailed, overflowRecovered, workRounds, endWorkRounds);
    }

    public RunState withPendingSteers(List<Message> newSteers) {
        return new RunState(ref, owner, phase, tools, newSteers, retries, workOrigin,
                compacting, autoCompactionFailed, overflowRecovered, workRounds, endWorkRounds);
    }

    public RunState withRetries(int newRetries) {
        return new RunState(ref, owner, phase, tools, pendingSteers, newRetries, workOrigin,
                compacting, autoCompactionFailed, overflowRecovered, workRounds, endWorkRounds);
    }

    public RunState withWorkOrigin(HookPoint newWorkOrigin) {
        return new RunState(ref, owner, phase, tools, pendingSteers, retries, newWorkOrigin,
                compacting, autoCompactionFailed, overflowRecovered, workRounds, endWorkRounds);
    }

    public RunState withCompacting(Compacting newCompacting) {
        return new RunState(ref, owner, phase, tools, pendingSteers, retries, workOrigin,
                newCompacting, autoCompactionFailed, overflowRecovered, workRounds, endWorkRounds);
    }

    public RunState withAutoCompactionFailed(boolean failed) {
        return new RunState(ref, owner, phase, tools, pendingSteers, retries, workOrigin,
                compacting, failed, overflowRecovered, workRounds, endWorkRounds);
    }

    public RunState withOverflowRecovered(boolean recovered) {
        return new RunState(ref, owner, phase, tools, pendingSteers, retries, workOrigin,
                compacting, autoCompactionFailed, recovered, workRounds, endWorkRounds);
    }

    public RunState withWorkRounds(int rounds) {
        return new RunState(ref, owner, phase, tools, pendingSteers, retries, workOrigin,
                compacting, autoCompactionFailed, overflowRecovered, rounds, endWorkRounds);
    }

    public RunState withEndWorkRounds(int rounds) {
        return new RunState(ref, owner, phase, tools, pendingSteers, retries, workOrigin,
                compacting, autoCompactionFailed, overflowRecovered, workRounds, rounds);
    }

    public boolean isEnded() {
        return phase instanceof Ended;
    }
}
