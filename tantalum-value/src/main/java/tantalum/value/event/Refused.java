package tantalum.value.event;

import java.util.Objects;

/**
 * 对方确定不收编号为 {@code id} 的送达（delivery 需求 2.2），进送方会话：从信箱划掉；是交代的，记成失败的结果。
 * 一时进不去（环满、读不了）不是不收，不会有这个事件。
 */
public record Refused(String id, String reason) implements Event {

    public static final String TYPE = "Refused";

    public Refused {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reason, "reason");
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
