package tantalum.examples.hooks;

import java.util.function.Predicate;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.state.KernelState;

/** 让一个钩子只对名字满足条件的会话生效，其余会话放行。名字和强制与否都照搬原钩子。 */
final class Scoped implements KernelHook {

    private final Predicate<String> sessions;
    private final KernelHook inner;

    Scoped(Predicate<String> sessions, KernelHook inner) {
        this.sessions = sessions;
        this.inner = inner;
    }

    private boolean mine(HookContext context) {
        return sessions.test(context.run().session().value());
    }

    @Override
    public String name() {
        return inner.name();
    }

    @Override
    public boolean mandatory() {
        return inner.mandatory();
    }

    @Override
    public HookResult beforeModelCall(KernelState state, HookContext context) {
        return mine(context) ? inner.beforeModelCall(state, context) : Allow.PLAIN;
    }

    @Override
    public HookResult beforeToolCall(KernelState state, HookContext context) {
        return mine(context) ? inner.beforeToolCall(state, context) : Allow.PLAIN;
    }

    @Override
    public HookResult onFailure(KernelState state, HookContext context) {
        return mine(context) ? inner.onFailure(state, context) : Allow.PLAIN;
    }

    @Override
    public HookResult beforeRunEnd(KernelState state, HookContext context) {
        return mine(context) ? inner.beforeRunEnd(state, context) : Allow.PLAIN;
    }

    @Override
    public HookResult onWorkResult(KernelState state, HookContext context) {
        return mine(context) ? inner.onWorkResult(state, context) : Allow.PLAIN;
    }

    @Override
    public HookResult beforeCompact(KernelState state, HookContext context) {
        return mine(context) ? inner.beforeCompact(state, context) : Allow.PLAIN;
    }
}
