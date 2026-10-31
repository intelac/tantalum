package tantalum.kernel.hook;

import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.state.KernelState;

/** 内核钩子。必须是纯函数；要做 I/O 就返回 {@code NeedWork}。 */
public interface KernelHook {

    String name();

    /** 强制钩子抛异常时按 {@code Stop} 处理。 */
    default boolean mandatory() {
        return false;
    }

    default HookResult beforeModelCall(KernelState state, HookContext context) {
        return Allow.PLAIN;
    }

    default HookResult beforeToolCall(KernelState state, HookContext context) {
        return Allow.PLAIN;
    }

    default HookResult onFailure(KernelState state, HookContext context) {
        return Allow.PLAIN;
    }

    default HookResult beforeRunEnd(KernelState state, HookContext context) {
        return Allow.PLAIN;
    }

    default HookResult onWorkResult(KernelState state, HookContext context) {
        return Allow.PLAIN;
    }
}
