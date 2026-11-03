package tantalum.examples;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.event.ModelFailed;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.json.Json;
import tantalum.value.state.KernelState;

/**
 * 提供方退避（{@code onFailure}）：用失败的原因和提供方给的等待时间决定怎么处理这次失败。
 * 参照 DSH {@code llm-retry} 等待太久就放弃（{@code packages/llm/llm-retry/src/index.ts:227-228}），
 * 以及 {@code compaction-image-offload} 的“先做恢复工作，再重试”。
 * 结果：限流且要等的比 {@code maxWait} 久 → {@code Stop}；服务端错误、连接错误 → {@code NeedWork}（{@link ProviderStatusWork} 等提供方恢复）；
 * 其余放行。上下文超长必须放行：这里要求工作会跳过内核内置的超长恢复。
 */
public final class ProviderBackoffHook implements KernelHook {

    private final Duration maxWait;

    public ProviderBackoffHook(Duration maxWait) {
        if (maxWait == null || maxWait.isNegative()) {
            throw new IllegalArgumentException("maxWait must not be negative but was " + maxWait);
        }
        this.maxWait = maxWait;
    }

    @Override
    public String name() {
        return "providerBackoff";
    }

    @Override
    public HookResult onFailure(KernelState state, HookContext context) {
        ModelFailed failure = context.failure();
        if (failure == null) {
            return Allow.PLAIN;
        }
        return switch (failure.reason()) {
            // 内核最多退避 5 分钟、重试 3 次；提供方要等更久时，几次重试注定白费。
            case RATE_LIMITED -> failure.retryAfter() != null && failure.retryAfter().compareTo(maxWait) > 0
                    ? new Stop("provider asks to wait " + failure.retryAfter().toSeconds() + "s, longer than the "
                            + maxWait.toSeconds() + "s this deployment accepts")
                    : Allow.PLAIN;
            case SERVER_ERROR, CONNECTION_ERROR -> new NeedWork(ProviderStatusWork.TYPE, input(failure));
            default -> Allow.PLAIN;
        };
    }

    /** 键按固定顺序放：输入会写进 journal，回放要逐字节相同。 */
    private static Json input(ModelFailed failure) {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("reason", new Json.Str(failure.reason().name()));
        fields.put("detail", new Json.Str(failure.detail() == null ? "" : failure.detail()));
        return new Json.Obj(fields);
    }
}
