package tantalum.value.command;

import java.util.Set;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;

/** 中止运行的在途命令。 */
public record AbortCommands(CommandId id, RunRef run, Set<CommandId> commands) implements Command {}
