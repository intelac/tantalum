package tantalum.examples;

import java.time.LocalDate;
import java.time.ZoneOffset;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.KernelState;
import tantalum.value.state.PrincipalState;

/** 每日额度：超过当天的 token 上限就停止。数据从事件投影到状态，时间取自事件，所以钩子是纯函数。 */
public final class DailyQuotaHook implements KernelHook {

    private final long dailyTokens;

    public DailyQuotaHook(long dailyTokens) {
        this.dailyTokens = dailyTokens;
    }

    @Override
    public String name() {
        return "dailyQuota";
    }

    @Override
    public boolean mandatory() {
        return true;
    }

    @Override
    public HookResult beforeModelCall(KernelState state, HookContext context) {
        PrincipalState principal = state.principals().get(context.run().principal());
        if (principal == null) {
            return Allow.PLAIN;
        }
        TokenUsage today = principal.usageOn(LocalDate.ofInstant(context.at(), ZoneOffset.UTC));
        return today.input() + today.output() >= dailyTokens ? new Stop("daily token quota used up") : Allow.PLAIN;
    }
}
