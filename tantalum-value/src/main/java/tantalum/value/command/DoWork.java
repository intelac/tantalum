package tantalum.value.command;

import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.json.Json;

/** 执行插件工作。 */
public record DoWork(CommandId id, RunRef run, String workType, Json input) implements Command {}
