package tantalum.value.state;

import tantalum.value.id.CommandId;
import tantalum.value.id.SessionId;

/** 运行的所有者：这一轮是为谁跑的。用户发起的归会话；为回另一个会话的话而开的归那句话。 */
public sealed interface OwnerRef {

    record OfSession(SessionId session) implements OwnerRef {}

    record OfCommand(CommandId command) implements OwnerRef {}

    /** 这一轮是为回另一个会话的一句话开的：{@code message} 是那句话的编号，{@code from} 是说话的会话，{@code hops} 是那句话转了几手。结束时把结果送回去。 */
    record OfMessage(CommandId message, SessionId from, int hops) implements OwnerRef {}
}
