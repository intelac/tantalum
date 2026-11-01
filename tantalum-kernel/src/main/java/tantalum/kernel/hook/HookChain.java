package tantalum.kernel.hook;

import java.util.ArrayList;
import java.util.List;
import tantalum.value.decision.Fact;
import tantalum.value.decision.HookReported;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.PromptPatch;
import tantalum.value.hook.Stop;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.KernelState;

/** 按注册顺序遍历钩子，并执行异常规则：强制钩子和 {@code beforeToolCall} 失败时关闭，其余跳过并上报；
 * 返回 null 违反契约，一律按 {@code Stop} 处理。 */
public final class HookChain {

    private final List<KernelHook> hooks;

    public HookChain(List<KernelHook> hooks) {
        this.hooks = List.copyOf(hooks);
    }

    public HookOutcome run(HookPoint point, KernelState state, HookContext context) {
        StringBuilder system = new StringBuilder();
        List<ToolSpec> tools = new ArrayList<>();
        List<Fact> facts = new ArrayList<>();

        for (KernelHook hook : hooks) {
            HookResult result;
            try {
                result = call(hook, point, state, context);
            } catch (RuntimeException failure) {
                facts.add(new HookReported(context.run(), hook.name(), failure.toString()));
                if (hook.mandatory() || point == HookPoint.BEFORE_TOOL_CALL) {
                    return new HookOutcome(new Stop("hook failed: " + hook.name()), facts);
                }
                continue;
            }
            if (result == null) {
                facts.add(new HookReported(context.run(), hook.name(), "hook returned null"));
                return new HookOutcome(new Stop("hook returned null: " + hook.name()), facts);
            }
            if (!(result instanceof Allow allow)) {
                return new HookOutcome(result, facts);
            }
            system.append(allow.patch().systemAppend());
            tools.addAll(allow.patch().addTools());
        }
        return new HookOutcome(new Allow(new PromptPatch(system.toString(), tools)), facts);
    }

    private static HookResult call(KernelHook hook, HookPoint point, KernelState state, HookContext context) {
        return switch (point) {
            case BEFORE_MODEL_CALL -> hook.beforeModelCall(state, context);
            case BEFORE_TOOL_CALL -> hook.beforeToolCall(state, context);
            case ON_FAILURE -> hook.onFailure(state, context);
            case BEFORE_RUN_END -> hook.beforeRunEnd(state, context);
            case ON_WORK_RESULT -> hook.onWorkResult(state, context);
        };
    }
}
