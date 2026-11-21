package tantalum.value.state;

import java.util.Objects;
import tantalum.value.hook.HookPoint;
import tantalum.value.id.RunRef;
import tantalum.value.model.ModelChoice;

/**
 * 一次运行的状态，分成四块：{@code cause} 是它被什么引起的；{@code flow} 是流程走到哪；{@code recovery} 是压缩和超长恢复；
 * {@code models} 是用的模型。system 和工具只存在会话的 {@code SessionContext} 里，这里不复制。
 * 到了、还没被模型看到的输入不在这里，在会话的收件队列里（{@link SessionState#inbox}）：一轮怎么结束，它们都还在。
 */
public record RunState(RunRef ref, RunCause cause, Flow flow, Recovery recovery, Models models) {

    /**
     * 流程：{@code workOrigin} 是发起当前工作的钩子位置；{@code workRounds} 是当前等待点连续做了几轮插件工作，
     * {@code endWorkRounds} 是本运行在结束前一共做了几轮，两者都由事件算出，回放得到同样的值。
     */
    public record Flow(RunPhase phase, ToolRound tools, int retries, HookPoint workOrigin, int workRounds, int endWorkRounds) {
        public Flow {
            Objects.requireNonNull(phase, "phase");
        }
    }

    /**
     * 压缩和超长恢复：{@code compacting} 在等摘要时有值；{@code autoCompactionFailed} 为真后本运行不再自动压缩；
     * {@code overflowRecovered} 为真后再超长就直接失败。
     */
    public record Recovery(Compacting compacting, boolean autoCompactionFailed, boolean overflowRecovered) {}

    /**
     * {@code model} 是这个运行用的模型，第一次组装上下文时定下，整个运行不变。{@code modelBefore} 是这个运行换掉的上一个模型：
     * 换到窗口更小的模型时，第一次调用前的压缩摘要用它写，压缩失败时会话退回它；第一次正常回复后清掉。
     */
    public record Models(ModelChoice model, ModelChoice modelBefore) {}

    public RunState {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(recovery, "recovery");
        Objects.requireNonNull(models, "models");
    }

    public static RunState start(RunRef ref, RunCause cause) {
        return new RunState(ref, cause, new Flow(new Deciding(), null, 0, null, 0, 0), new Recovery(null, false, false), new Models(null, null));
    }

    public RunPhase phase() {
        return flow.phase();
    }

    public ToolRound tools() {
        return flow.tools();
    }

    public int retries() {
        return flow.retries();
    }

    public HookPoint workOrigin() {
        return flow.workOrigin();
    }

    public int workRounds() {
        return flow.workRounds();
    }

    public int endWorkRounds() {
        return flow.endWorkRounds();
    }

    public Compacting compacting() {
        return recovery.compacting();
    }

    public boolean autoCompactionFailed() {
        return recovery.autoCompactionFailed();
    }

    public boolean overflowRecovered() {
        return recovery.overflowRecovered();
    }

    public ModelChoice model() {
        return models.model();
    }

    public ModelChoice modelBefore() {
        return models.modelBefore();
    }

    public RunState withCause(RunCause newCause) {
        return new RunState(ref, newCause, flow, recovery, models);
    }

    public RunState withFlow(Flow newFlow) {
        return new RunState(ref, cause, newFlow, recovery, models);
    }

    public RunState withRecovery(Recovery newRecovery) {
        return new RunState(ref, cause, flow, newRecovery, models);
    }

    public RunState withModels(Models newModels) {
        return new RunState(ref, cause, flow, recovery, newModels);
    }

    public RunState withPhase(RunPhase newPhase) {
        return withFlow(new Flow(newPhase, flow.tools(), flow.retries(), flow.workOrigin(), flow.workRounds(), flow.endWorkRounds()));
    }

    public RunState withTools(ToolRound newTools) {
        return withFlow(new Flow(flow.phase(), newTools, flow.retries(), flow.workOrigin(), flow.workRounds(), flow.endWorkRounds()));
    }

    public RunState withRetries(int newRetries) {
        return withFlow(new Flow(flow.phase(), flow.tools(), newRetries, flow.workOrigin(), flow.workRounds(), flow.endWorkRounds()));
    }

    public RunState withWorkOrigin(HookPoint newWorkOrigin) {
        return withFlow(new Flow(flow.phase(), flow.tools(), flow.retries(), newWorkOrigin, flow.workRounds(), flow.endWorkRounds()));
    }

    public RunState withWorkRounds(int rounds) {
        return withFlow(new Flow(flow.phase(), flow.tools(), flow.retries(), flow.workOrigin(), rounds, flow.endWorkRounds()));
    }

    public RunState withEndWorkRounds(int rounds) {
        return withFlow(new Flow(flow.phase(), flow.tools(), flow.retries(), flow.workOrigin(), flow.workRounds(), rounds));
    }

    public RunState withCompacting(Compacting newCompacting) {
        return withRecovery(new Recovery(newCompacting, recovery.autoCompactionFailed(), recovery.overflowRecovered()));
    }

    public RunState withAutoCompactionFailed(boolean failed) {
        return withRecovery(new Recovery(recovery.compacting(), failed, recovery.overflowRecovered()));
    }

    public RunState withOverflowRecovered(boolean recovered) {
        return withRecovery(new Recovery(recovery.compacting(), recovery.autoCompactionFailed(), recovered));
    }

    /** 定下这个运行的模型，和它换掉的上一个（没换为 null）。 */
    public RunState withModel(ModelChoice newModel, ModelChoice before) {
        return withModels(new Models(newModel, before));
    }

    public boolean isEnded() {
        return flow.phase() instanceof Ended;
    }
}
