package tantalum.value.delivery;

import java.util.Objects;
import tantalum.value.event.Assignment;

/**
 * 一次交代。帮手会话还没有时处理器据此建：{@code agent} 是模型点的名字（没点为 null），{@code dir}、{@code offRecord} 照交代它的会话。
 * 主会话派活时 {@code workspace} 是点名的工作区（处理器据此查目录建普通会话，dispatch），否则为 null。
 */
public record AssignmentParcel(Assignment input, String agent, String dir, boolean offRecord, String workspace) implements Parcel {

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
