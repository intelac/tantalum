package tantalum.shell.plugin;

import tantalum.value.json.Json;

/** 处理器钩子 {@code afterToolExecution}：可以做 I/O，只能修改数据，不能改变流程。 */
@FunctionalInterface
public interface AfterToolHook {

    ToolOutput afterToolExecution(ToolContext context, String tool, Json arguments, ToolOutput output) throws Exception;
}
