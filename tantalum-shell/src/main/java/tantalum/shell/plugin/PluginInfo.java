package tantalum.shell.plugin;

import tantalum.value.text.LocalText;

/**
 * 插件页要知道的事：说明、能不能关、默认开不开。钩子、工具后处理和插件工作里想在插件页上说点什么的
 * 另外实现这个接口；没实现的按“无说明、可以关、默认开”。所属插件仍由 {@code KernelHook.plugin()}、{@code AfterToolHook.plugin()} 说。
 * 内核的 {@code KernelHook} 不带这些：改文案不动内核 SDK。
 */
public interface PluginInfo {

    /**
     * 插件页上的一句说明，可以为空。写 {@code LocalText.of("…")} 照原样显示；写 {@code LocalText.en("…").zh("…").ja("…")}
     * 控制台按看的人的语言选，没有那种就用英文。
     */
    default LocalText description() {
        return LocalText.empty();
    }

    /**
     * 必须开启：插件页上不能关，它的钩子抛异常时按拦截处理。管理员也可以用配置 {@code tantalum.hooks.required} 把任何插件设成必须开启，
     * 效果相同。
     */
    default boolean mandatory() {
        return false;
    }

    /**
     * 用户没在插件页设置过时开不开，默认开。策略类插件（只读、工具审批、每日额度）返回 false，由用户自己打开（2026-11-06 Yi 定）。
     * 同一个插件里有一个成员返回 false，整个插件默认就关。必须开启的插件不看这一项。
     */
    default boolean defaultEnabled() {
        return true;
    }
}
