package tantalum.value.command;

import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;

/** 组装提示词。 */
public record AssembleContext(CommandId id, RunRef run) implements Command {}
