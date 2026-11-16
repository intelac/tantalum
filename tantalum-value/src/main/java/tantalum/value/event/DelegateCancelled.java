package tantalum.value.event;

import java.util.Objects;
import tantalum.value.id.CommandId;

/**
 * 父会话不再等这次交活了（kernel-delegation）：父运行被取消、失败，或者等到了期限。进子会话的账。它不是 {@link Cancel}（用户撤销运行）：
 * 账里看得出是父会话放弃的，而且只停 {@code delegation} 这一次交活开的运行或排队的条目，不动子会话里别的运行。
 */
public record DelegateCancelled(CommandId delegation) implements Event {

    public static final String TYPE = "DelegateCancelled";

    public DelegateCancelled {
        Objects.requireNonNull(delegation, "delegation");
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
