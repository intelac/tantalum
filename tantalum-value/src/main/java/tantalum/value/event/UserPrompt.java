package tantalum.value.event;

import java.util.List;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.AttachedBlock;
import tantalum.value.model.ModelChoice;
import tantalum.value.state.OwnerRef;

/**
 * 用户提问，也是运行的创建事件。{@code attachments} 按上传顺序，旧行读出来是空列表。
 * {@code model} 是提交里带的选择（“要用什么”），没带或旧行为 null。
 * {@code dir} 是会话的工作目录（用户电脑上的绝对路径），只有会话的第一次提问带它才算数；没选或旧行为 null。
 * {@code skills} 是用户在文字里点名的技能（{@code /name}），按出现顺序；没有或旧行是空列表。
 * {@code attached} 是插件在提交时附的内容（memory-plugin），按插件的顺序；没有或旧行是空列表。
 * {@code offRecord} 为 true 时这是“不进记忆”的会话（memory-final-plan 第 11 节），只有会话的第一次提问带它才算数；
 * 没带、旧行为 null（只写 true，旧行逐字节不变）。
 */
public record UserPrompt(SubmissionId submission, RequestId request, OwnerRef owner, String text,
                         List<AttachmentRef> attachments, ModelChoice model, String dir, List<SkillUse> skills,
                         List<AttachedBlock> attached, Boolean offRecord) implements Submitted {

    public static final String TYPE = "UserPrompt";

    public UserPrompt {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        skills = skills == null ? List.of() : List.copyOf(skills);
        attached = attached == null ? List.of() : List.copyOf(attached);
        offRecord = Boolean.TRUE.equals(offRecord) ? Boolean.TRUE : null;
    }

    public UserPrompt(SubmissionId submission, RequestId request, OwnerRef owner, String text, List<AttachmentRef> attachments,
                      ModelChoice model, String dir, List<SkillUse> skills, List<AttachedBlock> attached) {
        this(submission, request, owner, text, attachments, model, dir, skills, attached, null);
    }

    public UserPrompt(SubmissionId submission, RequestId request, OwnerRef owner, String text, List<AttachmentRef> attachments,
                      ModelChoice model, String dir, List<SkillUse> skills) {
        this(submission, request, owner, text, attachments, model, dir, skills, List.of());
    }

    public UserPrompt(SubmissionId submission, RequestId request, OwnerRef owner, String text, List<AttachmentRef> attachments,
                      ModelChoice model, String dir) {
        this(submission, request, owner, text, attachments, model, dir, List.of());
    }

    public UserPrompt(SubmissionId submission, RequestId request, OwnerRef owner, String text, List<AttachmentRef> attachments,
                      ModelChoice model) {
        this(submission, request, owner, text, attachments, model, null, List.of());
    }

    public UserPrompt(SubmissionId submission, RequestId request, OwnerRef owner, String text, List<AttachmentRef> attachments) {
        this(submission, request, owner, text, attachments, null, null, List.of());
    }

    public UserPrompt(SubmissionId submission, RequestId request, OwnerRef owner, String text) {
        this(submission, request, owner, text, List.of(), null, null, List.of());
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
