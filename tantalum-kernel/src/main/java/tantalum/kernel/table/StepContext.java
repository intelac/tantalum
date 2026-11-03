package tantalum.kernel.table;

import tantalum.kernel.hook.HookChain;
import tantalum.kernel.tool.ToolSafety;
import tantalum.value.event.EventEnvelope;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunState;

/** 处理函数的输入。{@code run} 在事件还没有对应运行时为空。两个上限由 {@code Kernel} 从 {@code Limits} 拆开传入，子包不回头依赖内核根包。 */
public record StepContext(KernelState state, EventEnvelope envelope, RunState run, HookChain hooks,
                          ToolSafety toolSafety, int maxParallelTools, int maxWorkRounds) {}
