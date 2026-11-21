package tantalum.value.delivery;

import java.util.Objects;
import tantalum.value.event.Assignment;

/**
 * 一次交代。帮手会话还没有时处理器据此建：{@code agent} 是模型点的名字（没点为 null），{@code dir}、{@code offRecord} 照交代它的会话。
 */
public record AssignmentParcel(Assignment input, String agent, String dir, boolean offRecord) implements Parcel {

    public AssignmentParcel {
        Objects.requireNonNull(input, "input");
    }

    @Override
    public String id() {
        return "assign:" + input.id();
    }

    @Override
    public String assignment() {
        return input.id();
    }
}
