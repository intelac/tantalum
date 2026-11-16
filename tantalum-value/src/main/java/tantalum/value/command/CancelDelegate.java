package tantalum.value.command;

import java.util.List;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

/**
 * 取消交出去的活（kernel-delegation）：往每个子会话的入口送一条 {@link tantalum.value.event.DelegateCancelled}。
 * 父运行被取消、失败或等到期限时发。
 */
public record CancelDelegate(CommandId id, RunRef run, List<Target> targets) implements Command {

    /** 哪个子会话的哪一次交活。 */
    public record Target(SessionId child, CommandId delegation) {}

    public CancelDelegate {
        targets = List.copyOf(targets);
    }
}
