package tantalum.local.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * 服务器→电脑：要 {@code AGENTS.md} 的内容，回复 {@link ContextResult}。{@code dir} 是会话的工作目录（协议 3 起），从那里读；
 * 没有时为 null，不写出，从主目录读。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Context(String id, String dir) implements Message {

    public Context {
        Objects.requireNonNull(id, "id");
    }

    /** 从主目录读。 */
    public Context(String id) {
        this(id, null);
    }
}
