package tantalum.examples;

import java.util.Set;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.state.KernelState;

/** 只读策略：只放行列出的工具，其余拦截，模型会收到错误结果。 */
public final class ReadOnlyHook implements KernelHook {

    private final Set<String> readOnlyTools;

    public ReadOnlyHook(Set<String> readOnlyTools) {
        this.readOnlyTools = Set.copyOf(readOnlyTools);
    }

    @Override
    public String name() {
        return "readOnly";
    }

    @Override
    public String description() {
        return "只放行名单里的工具，其余调用一律拦下。";
    }

    @Override
    public boolean mandatory() {
        return true;
    }

    @Override
    public HookResult beforeToolCall(KernelState state, HookContext context) {
        String tool = context.call().name();
        return readOnlyTools.contains(tool) ? Allow.PLAIN : new Stop("read-only policy blocks " + tool);
    }
}
