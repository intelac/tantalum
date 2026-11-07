package tantalum.kernel.hook;

import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.state.KernelState;

/**
 * 把一个钩子包成出错一律拦截的（review-fixes 需求 12.4）：回调一个不少地转给原钩子，只有 {@link #failClosed()} 恒真。
 * 哪些插件必须开启由 shell 决定（自己声明的或管理员配置的），内核只认钩子自己说的 failClosed。
 */
public record FailClosed(KernelHook inner) implements KernelHook {
    @Override public String name() { return inner.name(); }
    @Override public String plugin() { return inner.plugin(); }
    @Override public boolean failClosed() { return true; }
    @Override public HookResult beforeModelCall(KernelState state, HookContext context) { return inner.beforeModelCall(state, context); }
    @Override public HookResult beforeToolCall(KernelState state, HookContext context) { return inner.beforeToolCall(state, context); }
    @Override public HookResult onFailure(KernelState state, HookContext context) { return inner.onFailure(state, context); }
    @Override public HookResult beforeRunEnd(KernelState state, HookContext context) { return inner.beforeRunEnd(state, context); }
    @Override public HookResult onWorkResult(KernelState state, HookContext context) { return inner.onWorkResult(state, context); }
    @Override public HookResult beforeCompact(KernelState state, HookContext context) { return inner.beforeCompact(state, context); }
    @Override public HookResult onAnswer(KernelState state, HookContext context) { return inner.onAnswer(state, context); }
}
