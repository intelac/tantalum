package tantalum.value.command;

import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

/**
 * 回确认：告诉会话 {@code to}，它送来的那句话（编号 {@code message}）已经进了这边的账。处理器把一条 {@code MessageDelivered}
 * 放进 {@code to} 的入口，不等、不读任何会话的状态。{@code run} 是发这条命令的地方（不一定有运行）。
 */
public record Confirm(CommandId id, RunRef run, SessionId to, CommandId message) implements Command {}
