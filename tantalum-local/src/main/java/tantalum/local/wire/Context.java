package tantalum.local.wire;

import java.util.Objects;

/** 服务器→电脑：要主目录里 {@code AGENTS.md} 的内容，回复 {@link ContextResult}。 */
public record Context(String id) implements Message {

    public Context {
        Objects.requireNonNull(id, "id");
    }
}
