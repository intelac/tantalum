package tantalum.local.wire;

import java.util.Objects;

/** 电脑→服务器：{@code AGENTS.md} 的内容，最多 {@value #MAX_AGENTS_BYTES} 字节；没有这个文件时为 null。 */
public record ContextResult(String id, String agents) implements Message {

    /** 同服务器上原来读 {@code AGENTS.md} 的上限。 */
    public static final int MAX_AGENTS_BYTES = 64 * 1024;

    public ContextResult {
        Objects.requireNonNull(id, "id");
    }
}
