package tantalum.shell.plugin;

import tantalum.value.json.Json;
import tantalum.value.text.LocalText;

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

    /** 插件页上的一句说明，写法同 {@code KernelHook.description()}。 */
    default LocalText description() {
        return LocalText.empty();
    }
}
