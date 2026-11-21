package tantalum.value.delivery;

import java.util.Objects;
import tantalum.value.event.Notice;

/** 记一笔，送给开这个会话的主会话（dispatch 需求 3）。不属于哪个交代：{@link #assignment()} 为 null。 */
public record NoticeParcel(Notice input) implements Parcel {

    public NoticeParcel {
        Objects.requireNonNull(input, "input");
    }

    @Override
    public String id() {
        return "notice:" + input.id();
    }

    @Override
    public String assignment() {
        return null;
    }
}
