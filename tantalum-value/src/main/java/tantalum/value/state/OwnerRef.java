package tantalum.value.state;

import tantalum.value.id.CommandId;
import tantalum.value.id.SessionId;

/** 运行的所有者：这一轮是为谁跑的。用户发起的归会话；为回另一个会话的话而开的归那句话。 */
public sealed interface OwnerRef {

    record OfSession(SessionId session) implements OwnerRef {}

    record OfCommand(CommandId command) implements OwnerRef {}

    /** 这一轮是为回另一个会话的一句话开的：{@code message} 是那句话的编号，{@code from} 是说话的会话，{@code hops} 是那句话转了几手。结束时把结果送回去。 */
    record OfMessage(CommandId message, SessionId from, int hops, boolean toldWaiting, boolean userSpoke) implements OwnerRef {

        public OfMessage(CommandId message, SessionId from, int hops) {
            this(message, from, hops, false, false);
        }

        public OfMessage(CommandId message, SessionId from, int hops, boolean toldWaiting) {
            this(message, from, hops, toldWaiting, false);
        }

        /** 已经告诉过说话的会话“这一轮在等用户回答”（一轮只说一次）。 */
        public OfMessage told() {
            return new OfMessage(message, from, hops, true, userSpoke);
        }

        /** 这一轮做的时候用户在这个会话里插过话：回话里带上这个事实，说话的会话才知道结果可能已经不是它原来要的。 */
        public OfMessage spoke() {
            return new OfMessage(message, from, hops, toldWaiting, true);
        }
    }
}
