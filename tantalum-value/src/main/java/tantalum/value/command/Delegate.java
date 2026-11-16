package tantalum.value.command;

import tantalum.value.event.Delegated;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

/**
 * 交活（kernel-delegation）：把 {@code handoff} 送进子会话 {@code child} 的入口。处理器只送，不等、不读别的会话；
 * 送不进去时往父会话送一条“没开成”的交回。{@code run} 是发这条命令的父运行。
 */
public record Delegate(CommandId id, RunRef run, SessionId child, Delegated handoff) implements Command {}
