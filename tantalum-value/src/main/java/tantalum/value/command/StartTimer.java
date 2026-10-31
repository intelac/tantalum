package tantalum.value.command;

import java.time.Duration;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;

/** 启动定时器，用于重试退避。 */
public record StartTimer(CommandId id, RunRef run, Duration delay) implements Command {}
