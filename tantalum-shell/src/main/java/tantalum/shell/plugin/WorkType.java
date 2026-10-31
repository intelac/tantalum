package tantalum.shell.plugin;

import tantalum.value.json.Json;

/** 插件注册的工作类型，内核钩子返回 {@code NeedWork} 时执行。可以做 I/O。 */
public interface WorkType {

    String name();

    WorkResult run(ToolContext context, Json input) throws Exception;
}
