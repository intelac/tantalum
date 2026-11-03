package tantalum.shell.plugin;

import tantalum.value.json.Json;

/** 插件注册的工作类型，内核钩子返回 {@code NeedWork} 时执行。可以做 I/O。 */
public interface WorkType {

    String name();

    /** 所属插件，同 {@code KernelHook.plugin()}；默认是 {@link #name()}。 */
    default String plugin() {
        return name();
    }

    default String description() {
        return "";
    }

    WorkResult run(ToolContext context, Json input) throws Exception;
}
