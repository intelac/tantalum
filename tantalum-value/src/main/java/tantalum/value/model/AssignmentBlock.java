package tantalum.value.model;

import java.util.Objects;

/**
 * 另一个会话交代的内容在历史里的样子。不是人说的话，靠类型认：记忆、标题、轨迹都不把它当成你的话。
 * 出现在 {@link Role#USER} 消息里；发给模型的就是 {@code text} 本身，和一段普通文字一样。
 */
public record AssignmentBlock(String assignment, String text) implements ContentBlock {
    public AssignmentBlock {
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(text, "text");
    }
}
