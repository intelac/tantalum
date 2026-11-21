package tantalum.value.delivery;

import java.util.Objects;
import tantalum.value.id.CommandId;
import tantalum.value.id.SessionId;

/**
 * 送方信箱里的一条（delivery 需求 1）：送给同一个人的会话 {@code to}，对方确认收到才划掉。{@code ackTimer} 非空表示已经发出、在等确认，
 * 到时没确认就照原编号重送；同一个 {@code to} 前一条确认了才发下一条。
 */
public record Outgoing(SessionId to, Parcel what, CommandId ackTimer) {

    public Outgoing {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(what, "what");
    }

    public String id() {
        return what.id();
    }

    public Outgoing sent(CommandId timer) {
        return new Outgoing(to, what, timer);
    }
}
