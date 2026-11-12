package tantalum.shell.plugin;

import java.util.Optional;
import java.util.OptionalLong;
import tantalum.value.hook.Params;

/**
 * 插件的成员（钩子、工具后处理、插件工作）实现它，告诉平台“这个人每天最多用多少 token”。平台只拿来给用户看
 * （输入框旁的“今日 已用 / 上限”），拦不拦还是插件自己的钩子说了算；平台不认识任何具体插件。
 * 几个开着的插件都报了上限时取最小的。
 */
public interface DailyLimit {

    /** 按这个人此刻生效的这包参数，一天最多多少 token；不限返回空。量和 {@code TokenUsage.counted()} 同一个算法（配了折算系数就是折算后的）。 */
    OptionalLong dailyTokens(Params params);

    /**
     * 上限是这个插件的哪个参数（参数的键）。说了，运维页就能在表格里直接显示这个人的上限是默认的还是单独设的，并就地修改
     * （qagent-align 需求 5）；平台仍然不认识具体插件。要是归管理员的数字参数才算数。默认不说。
     */
    default Optional<String> limitParam() {
        return Optional.empty();
    }
}
