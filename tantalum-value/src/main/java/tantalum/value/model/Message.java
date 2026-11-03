package tantalum.value.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tantalum.value.attachment.AttachmentRef;

/** 对话中的一条消息。{@code interrupted} 表示回复被取消，内容只是用户已经看到的部分。 */
public record Message(Role role, List<ContentBlock> content, boolean interrupted) {

    public Message {
        Objects.requireNonNull(role, "role");
        content = List.copyOf(content);
    }

    public static Message user(String text) {
        return new Message(Role.USER, List.of(new TextBlock(text)), false);
    }

    /** 用户消息：先文字，再按上传顺序的附件。只有附件时文字为空串，由各客户端按需补（设计第 1 节）。 */
    public static Message user(String text, List<AttachmentRef> attachments) {
        if (attachments.isEmpty()) {
            return user(text);
        }
        List<ContentBlock> content = new ArrayList<>();
        content.add(new TextBlock(text));
        attachments.forEach(ref -> content.add(new AttachmentBlock(ref)));
        return new Message(Role.USER, content, false);
    }
}
