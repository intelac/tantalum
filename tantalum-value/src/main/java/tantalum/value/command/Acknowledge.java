package tantalum.value.command;

import java.util.Objects;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

/**
 * 回给送方 {@code to}（同一个人的会话）：{@code refused} 为 null 是“送到了”（{@code Delivered}），否则是“不收”的原因（{@code Refused}）。
 * 本身不再确认：丢了送方会重送，收方认出重复再回一次。
 */
public record Acknowledge(CommandId id, RunRef run, String delivery, SessionId to, String refused) implements Command {

    public Acknowledge {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(to, "to");
    }
}
