package tantalum.value.event;

import java.util.List;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.AttachedBlock;
import tantalum.value.model.ModelChoice;

/**
 * 排队提交，运行结束后开始下一轮。{@code attachments} 按上传顺序，旧行读出来是空列表。
 * {@code model} 是提交里带的选择，排队后开始的运行用它，没带或旧行为 null。
 * {@code skills} 是文字里点名的技能，在排队的那一刻读好；没有或旧行是空列表。
 * {@code attached} 是插件在提交时附的内容（memory-plugin）；没有或旧行是空列表。
 */
public record FollowUpSubmitted(SubmissionId submission, RequestId request, String text, List<AttachmentRef> attachments,
                                ModelChoice model, List<SkillUse> skills, List<AttachedBlock> attached) implements Submitted {

    public static final String TYPE = "FollowUpSubmitted";

    public FollowUpSubmitted {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        skills = skills == null ? List.of() : List.copyOf(skills);
        attached = attached == null ? List.of() : List.copyOf(attached);
    }

    public FollowUpSubmitted(SubmissionId submission, RequestId request, String text, List<AttachmentRef> attachments, ModelChoice model,
                             List<SkillUse> skills) {
        this(submission, request, text, attachments, model, skills, List.of());
    }

    public FollowUpSubmitted(SubmissionId submission, RequestId request, String text, List<AttachmentRef> attachments, ModelChoice model) {
        this(submission, request, text, attachments, model, List.of());
    }

    public FollowUpSubmitted(SubmissionId submission, RequestId request, String text, List<AttachmentRef> attachments) {
        this(submission, request, text, attachments, null, List.of());
    }

    public FollowUpSubmitted(SubmissionId submission, RequestId request, String text) {
        this(submission, request, text, List.of(), null, List.of());
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
