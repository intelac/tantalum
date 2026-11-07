package tantalum.examples;

import java.time.LocalDate;
import java.time.ZoneOffset;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.plugin.PluginInfo;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.KernelState;
import tantalum.value.state.PrincipalState;
import tantalum.value.text.LocalText;

/** 每日额度：超过当天的 token 上限就停止。数据从事件投影到状态，时间取自事件，所以钩子是纯函数。 */
public final class DailyQuotaHook implements KernelHook, PluginInfo {

    private final long dailyTokens;

    public DailyQuotaHook(long dailyTokens) {
        if (dailyTokens <= 0) {
            throw new IllegalArgumentException("dailyTokens must be positive but was " + dailyTokens);
        }
        this.dailyTokens = dailyTokens;
    }

    @Override
    public String name() {
        return "dailyQuota";
    }

    @Override
    public LocalText description() {
        // 例子插件示范带三种语言的写法：控制台按看的人的语言选，没有就用英文
        return LocalText.en("Stops a user's runs after the daily token limit.")
                .zh("每个用户每天的 token 用量上限，超过就停止。")
                .ja("ユーザーごとの 1 日のトークン上限です。超えると実行を止めます。");
    }

    /** 策略插件：默认关，用户在插件页打开（管理员要对所有人强制开启时写进 {@code tantalum.hooks.required}）；开着时出错按拦截处理。 */
    @Override
    public boolean defaultEnabled() {
        return false;
    }

    @Override
    public boolean failClosed() {
        return true;
    }

    @Override
    public HookResult beforeModelCall(KernelState state, HookContext context) {
        PrincipalState principal = state.principals().get(context.run().principal());
        if (principal == null) {
            return Allow.PLAIN;
        }
        TokenUsage today = principal.usageOn(LocalDate.ofInstant(context.at(), ZoneOffset.UTC));
        return today.promptTotal() + today.output() >= dailyTokens ? new Stop("daily token quota used up") : Allow.PLAIN;
    }
}
