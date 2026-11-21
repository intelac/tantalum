package tantalum.value.event;

import java.util.Objects;

/** 对方收到了编号为 {@code id} 的送达（delivery 需求 2），进送方会话：从信箱划掉，发同一个对方的下一条。只由处理器放进来。 */
public record Delivered(String id) implements Event {

    public static final String TYPE = "Delivered";

    public Delivered {
        Objects.requireNonNull(id, "id");
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
