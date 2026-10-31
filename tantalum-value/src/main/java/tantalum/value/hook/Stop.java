package tantalum.value.hook;

import java.util.Objects;

/** 停止：结束运行或拦截工具，由钩子位置决定。 */
public record Stop(String reason) implements HookResult {

    public Stop {
        Objects.requireNonNull(reason, "reason");
    }
}
