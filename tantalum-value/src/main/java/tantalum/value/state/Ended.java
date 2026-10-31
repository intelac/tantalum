package tantalum.value.state;

import java.util.Objects;

/** 运行已结束。 */
public record Ended(EndReason reason, String detail) implements RunPhase {

    public Ended {
        Objects.requireNonNull(reason, "reason");
    }
}
