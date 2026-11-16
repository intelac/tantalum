package tantalum.value.command;

import tantalum.value.event.DelegateReturned;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

/**
 * 交回（kernel-delegation）：把 {@code outcome} 送进父会话 {@code parent} 的入口。由子会话的内核在一轮结束（或问了问题）时发：
 * 这一轮的主人不是会话自己，结局要交回给主人。{@code run} 是发这条命令的子运行。
 */
public record Return(CommandId id, RunRef run, SessionId parent, DelegateReturned outcome) implements Command {}
