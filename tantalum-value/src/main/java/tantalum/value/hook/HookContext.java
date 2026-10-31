package tantalum.value.hook;

import java.time.Instant;
import java.util.Objects;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.WorkDone;
import tantalum.value.id.RunRef;
import tantalum.value.model.ToolCallBlock;

/** 钩子能看到的上下文，时间取自事件。{@code call}、{@code failure}、{@code work} 只在对应的钩子位置有值。 */
public record HookContext(RunRef run, Instant at, ToolCallBlock call, ModelFailed failure, WorkDone work) {

    public HookContext {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(at, "at");
    }

    public static HookContext of(RunRef run, Instant at) {
        return new HookContext(run, at, null, null, null);
    }

    public HookContext withCall(ToolCallBlock toolCall) {
        return new HookContext(run, at, toolCall, failure, work);
    }

    public HookContext withFailure(ModelFailed modelFailure) {
        return new HookContext(run, at, call, modelFailure, work);
    }

    public HookContext withWork(WorkDone workDone) {
        return new HookContext(run, at, call, failure, workDone);
    }
}
