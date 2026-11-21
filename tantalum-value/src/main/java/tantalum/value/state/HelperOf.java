package tantalum.value.state;

import java.util.List;
import java.util.Objects;
import tantalum.value.id.SessionId;

/**
 * 一个帮手会话是为谁的哪次交代开的，建好不变。{@code agent} 是用的 agent 的名字（默认的为 null），
 * {@code instructions} 是它的提示词（没有为 null），{@code tools} 是它能用的工具名（不限为 null）；三样都在交代时定下，回放不读文件。
 */
public record HelperOf(SessionId issuer, String assignment, String agent, String instructions, List<String> tools) {
    public HelperOf {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(assignment, "assignment");
        tools = tools == null ? null : List.copyOf(tools);
    }
}
