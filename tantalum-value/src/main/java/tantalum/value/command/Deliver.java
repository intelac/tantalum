package tantalum.value.command;

import java.util.Objects;
import tantalum.value.delivery.Parcel;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

/**
 * 把信箱里的一条送进 {@code to}（和 {@code run} 同一个人的会话）。处理器不重试、不等：送进去了等对方确认；一时进不去什么都不做（到时重送）；
 * 确定不收时回一条 {@code Refused} 作为这条命令的结果。{@code run} 是发它的会话里的一个运行引用（没有运行时按事件序号起一个）。
 */
public record Deliver(CommandId id, RunRef run, SessionId to, Parcel what) implements Command {

    public Deliver {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(what, "what");
    }
}
