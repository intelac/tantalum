package tantalum.local.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * 服务器→电脑：要这次运行开头用的东西，回复 {@link ContextResult}。{@code dir} 是会话的工作目录（协议 3 起），从那里读 {@code AGENTS.md}；
 * 没有时为 null，不写出，从主目录读。{@code skills}、{@code wiki}（协议 4 起）是用户填的技能目录、wiki 目录：带了哪个就列哪个，
 * 没带的为 null，不写出。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Context(String id, String dir, String skills, String wiki) implements Message {

    public Context {
        Objects.requireNonNull(id, "id");
    }

}
