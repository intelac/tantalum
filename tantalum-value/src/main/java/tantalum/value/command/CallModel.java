package tantalum.value.command;

import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.model.Prompt;

/** 调用模型，携带完整输入。 */
public record CallModel(CommandId id, RunRef run, Prompt prompt) implements Command {}
