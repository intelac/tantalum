package tantalum.kernel.hook;

import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.state.KernelState;

/**
 * 内核钩子。在内核的单线程上运行，回放时会被再次调用，所以必须是纯函数：同样的状态和上下文给出同样的结果，时间只取 {@code context.at()}。
 * 每个位置能返回什么、结果怎么生效，见各方法的注释；只有 {@code beforeModelCall} 的 {@code Allow} 补丁生效，其余位置忽略补丁。
 * <ol>
 *   <li>I/O 只能经 {@code NeedWork} 交给有超时的 {@code WorkType}，钩子里不读文件、不联网、不取时钟。</li>
 *   <li>{@code onWorkResult} 对所有工作都会调用，包括别的插件要的；钩子要先按 {@code work.workType()} 过滤。</li>
 *   <li>失败的工作结果（超时、抛异常、连不上）里只有错误说明，不带原来的输入；下一轮要用的信息得由工作在成功结果里带回。</li>
 *   <li>{@code onFailure} 返回 {@code NeedWork} 会跳过内置的超长恢复，所以上下文超长（{@code CONTEXT_OVERFLOW}）要放行。</li>
 *   <li>一个等待点连续要求工作有上限（{@code tantalum.hooks.max-work-rounds}，默认 8），超过时运行以失败结束；
 *       {@code onWorkResult} 接着要的工作记在原来的等待点上，{@code beforeRunEnd} 的轮数整个运行累计。</li>
 * </ol>
 */
public interface KernelHook {

    String name();

    /** 强制钩子抛异常时按 {@code Stop} 处理；非强制的跳过并上报（{@code beforeToolCall} 例外，抛异常一律拦截）。 */
    default boolean mandatory() {
        return false;
    }

    /** 调模型前。放行时补丁生效（追加 system、工具）；{@code Stop} 结束运行；工作做完后再问一次，{@code context.work()} 是结果。 */
    default HookResult beforeModelCall(KernelState state, HookContext context) {
        return Allow.PLAIN;
    }

    /** 每个工具调用前，按顺序一个一个问。{@code Stop} 只拦这个调用，模型收到“被拦下”；要求工作后不再问这里，由 {@code onWorkResult} 决定放不放。 */
    default HookResult beforeToolCall(KernelState state, HookContext context) {
        return Allow.PLAIN;
    }

    /** 模型调用失败后，{@code context.failure()} 是失败。放行交给内核（超长恢复、退避重试）；{@code Stop} 运行失败；工作做完后重试一次，占用重试次数。 */
    default HookResult onFailure(KernelState state, HookContext context) {
        return Allow.PLAIN;
    }

    /** 运行要正常结束前。{@code Stop} 结束为 STOPPED；工作带了 {@code continueText} 时回到模型，否则再问一次，{@code context.work()} 是结果。 */
    default HookResult beforeRunEnd(KernelState state, HookContext context) {
        return Allow.PLAIN;
    }

    /** 任何工作做完后，{@code context.work()} 是结果。放行回到原处；{@code Stop} 在工具调用前只拦那个调用，其余位置结束运行；{@code NeedWork} 再要一轮。 */
    default HookResult onWorkResult(KernelState state, HookContext context) {
        return Allow.PLAIN;
    }

    /**
     * 压缩前，计划在 {@code context.plan()}，触发方式在 {@code context.trigger()}。只能放行或 {@code Stop}，{@code NeedWork} 当作拒绝。
     * 拒绝的后果看触发方式：自动压缩只是本运行不再自动压缩；手动压缩以拒绝原因失败；超长恢复被拒，运行以原来的错误失败。
     */
    default HookResult beforeCompact(KernelState state, HookContext context) {
        return Allow.PLAIN;
    }
}
