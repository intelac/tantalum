package tantalum.value.command;

import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.model.ToolCallBlock;

/** 执行一次工具调用。 */
public record RunTool(CommandId id, RunRef run, ToolCallBlock call) implements Command {}
