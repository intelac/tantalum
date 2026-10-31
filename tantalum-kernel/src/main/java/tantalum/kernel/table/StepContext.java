package tantalum.kernel.table;

import tantalum.kernel.hook.HookChain;
import tantalum.kernel.tool.ToolSafety;
import tantalum.value.event.EventEnvelope;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunState;

/** 处理函数的输入。{@code run} 在事件还没有对应运行时为空。 */
public record StepContext(KernelState state, EventEnvelope envelope, RunState run, HookChain hooks,
                          ToolSafety toolSafety, int maxParallelTools) {}
