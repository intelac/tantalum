package tantalum.value.command;

import tantalum.value.event.SessionMessage;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

/**
 * 送话：把 {@code message} 送进会话 {@code to} 的入口。两个地方会发它：模型调 {@code delegate} 时；一轮为回一句话而开、结束时
 * 把结果送回去。处理器只送，不等、不读任何会话的状态。{@code run} 是发这条命令的运行。
 */
public record Send(CommandId id, RunRef run, SessionId to, SessionMessage message) implements Command {}
