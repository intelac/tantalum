package tantalum.shell.plugin;

import tantalum.value.json.Json;

/** 处理器钩子 {@code afterToolExecution}：可以做 I/O，只能修改数据，不能改变流程。 */
@FunctionalInterface
public interface AfterToolHook {

    ToolOutput afterToolExecution(ToolContext context, String tool, Json arguments, ToolOutput output) throws Exception;

    /**
     * 所属插件，同 {@code KernelHook.plugin()}。默认取类名；lambda 去掉编译器生成的后缀，所以写成 lambda 的工具后处理最好覆盖它，
     * 否则同一个文件里的几个 lambda 会被当成同一个插件。
     */
    default String plugin() {
        String name = getClass().getSimpleName();
        int lambda = name.indexOf("$$");
        return lambda > 0 ? name.substring(0, lambda) : name;
    }

    /**
     * 抛异常时这次工具调用以错误结束，而不是记日志、结果保持上一个（review-fixes 需求 12.7）。默认 false；必须开启的插件
     * （自己声明或管理员配置）一律按 true。插件页的说明另见 {@link PluginInfo}。
     */
    default boolean failClosed() {
        return false;
    }
}
