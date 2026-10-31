package tantalum.value.model;

import java.util.List;
import java.util.Objects;

/** 对话中的一条消息。{@code interrupted} 表示回复被取消，内容只是用户已经看到的部分。 */
public record Message(Role role, List<ContentBlock> content, boolean interrupted) {

    public Message {
        Objects.requireNonNull(role, "role");
        content = List.copyOf(content);
    }

    public static Message user(String text) {
        return new Message(Role.USER, List.of(new TextBlock(text)), false);
    }
}
