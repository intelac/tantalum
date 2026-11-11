package tantalum.local.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * 电脑→服务器：{@code AGENTS.md} 的内容，最多 {@value #MAX_AGENTS_BYTES} 字节，没有这个文件时为 null；{@link Context} 里带了技能目录、
 * wiki 目录时（协议 4 起）还有那两个目录里有什么，没问的为 null，不写出。
 */
public record ContextResult(
        String id,
        String agents,
        @JsonInclude(JsonInclude.Include.NON_NULL) SkillList skills,
        @JsonInclude(JsonInclude.Include.NON_NULL) WikiList wiki) implements Message {

    /** 同服务器上原来读 {@code AGENTS.md} 的上限。 */
    public static final int MAX_AGENTS_BYTES = 64 * 1024;

    public ContextResult {
        Objects.requireNonNull(id, "id");
    }

    /** 只有 {@code AGENTS.md}。 */
    public ContextResult(String id, String agents) {
        this(id, agents, null, null);
    }
}
