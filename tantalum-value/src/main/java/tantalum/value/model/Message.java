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

    /** 同上，再接插件附加的内容（memory-plugin），按插件的顺序。 */
    public static Message user(String text, List<AttachmentRef> attachments, List<SkillBlock> skills, List<AttachedBlock> attached) {
        Message withSkills = user(text, attachments, skills);
        if (attached.isEmpty()) {
            return withSkills;
        }
        List<ContentBlock> content = new ArrayList<>(withSkills.content());
        content.addAll(attached);
        return new Message(Role.USER, content, false);
    }

    /** 同上，再接用户点名的技能内容，按点名的顺序。 */
    public static Message user(String text, List<AttachmentRef> attachments, List<SkillBlock> skills) {
        Message plain = user(text, attachments);
        if (skills.isEmpty()) {
            return plain;
        }
        List<ContentBlock> content = new ArrayList<>(plain.content());
        content.addAll(skills);
        return new Message(Role.USER, content, false);
    }

    /** 另一个会话交代的内容。 */
    public static Message assignment(String assignment, String text) {
        return new Message(Role.USER, List.of(new AssignmentBlock(assignment, text)), false);
    }

    /**
     * 这条是不是你说的：角色是用户，不是交代、不是交代的结果，也不是内核写的上下文更新。记笔记、标题和搜索、轨迹里的“用户输入”、
     * 找回记忆时“哪些不是你写的”，都问这一个方法。
     */
    public boolean fromUser() {
        if (role != Role.USER || Summaries.isSummary(this)) {
            return false;
        }
        for (ContentBlock block : content) {
            if (block instanceof AssignmentBlock || block instanceof ReportBlock) {
                return false;
            }
            if (block instanceof TextBlock text && SystemUpdates.isContextUpdate(text.text())) {
                return false;
            }
        }
        return true;
    }
}
