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

    /**
     * 个人的插件（memory-final-plan 第 13 节，Yi 2026-11-15 定）：它的内容在管理员只读查看时一律遮掉；管理员只能强制关掉它，
     * 不能强制打开（锁成开着会被拒，写进 {@code tantalum.hooks.required} 时启动失败）。同一个插件里有一个成员返回 true 就算。
     */
    default boolean personal() {
        return false;
    }

    /**
     * 开着时给模型的一段固定说明（英文，一两句），没有为 null。放进上下文的“插件说明”一段，开关变了由内核追加变化，不改 system。
     * 用来让模型事先知道规矩，比如“改文件要用户批准”（QAgent 问题 10），不要写会变的内容。
     */
    default String modelNote() {
        return null;
    }
}
