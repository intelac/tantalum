package tantalum.shell.plugin;

import tantalum.value.json.Json;
import tantalum.value.text.LocalText;

/** 插件注册的工作类型，内核钩子返回 {@code NeedWork} 时执行。可以做 I/O。 */
public interface WorkType {

    String name();

    /** 所属插件，同 {@code KernelHook.plugin()}；默认是 {@link #name()}。 */
    default String plugin() {
        return name();
    }

    /** 插件页上的一句说明，写法同 {@code KernelHook.description()}。 */
    default LocalText description() {
        return LocalText.empty();
    }

    WorkResult run(ToolContext context, Json input) throws Exception;
}
