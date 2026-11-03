package tantalum.value.hook;

import java.time.Instant;
import java.util.Objects;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.WorkDone;
import tantalum.value.id.RunRef;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.Compacting;
import tantalum.value.state.Plan;

/**
 * 钩子能看到的上下文，时间取自事件。{@code call}、{@code failure}、{@code work}、{@code plan} 只在对应的钩子位置有值；
 * {@code trigger} 和 {@code plan} 一起只在压缩前有值：拒绝超长恢复会让运行失败，拒绝自动压缩只是这次不压，钩子要分得清。
 */
public record HookContext(RunRef run, Instant at, ToolCallBlock call, ModelFailed failure, WorkDone work,
                          Plan plan, Compacting.Trigger trigger) {

    public HookContext {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(at, "at");
    }

    public static HookContext of(RunRef run, Instant at) {
        return new HookContext(run, at, null, null, null, null, null);
    }

    public HookContext withCall(ToolCallBlock toolCall) {
        return new HookContext(run, at, toolCall, failure, work, plan, trigger);
    }

    public HookContext withFailure(ModelFailed modelFailure) {
        return new HookContext(run, at, call, modelFailure, work, plan, trigger);
    }

    public HookContext withWork(WorkDone workDone) {
        return new HookContext(run, at, call, failure, workDone, plan, trigger);
    }

    public HookContext withCompaction(Plan compactionPlan, Compacting.Trigger compactionTrigger) {
        return new HookContext(run, at, call, failure, work, compactionPlan, compactionTrigger);
    }
}
