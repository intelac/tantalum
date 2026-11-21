package tantalum.value.event;

import java.util.List;
import java.util.Objects;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.AttachedBlock;
import tantalum.value.model.ModelChoice;

/**
 * 你说的一句话。{@code attachments} 按上传顺序。{@code model} 是提交里带的选择（“要用什么”），没带为 null；
 * 并进正在跑的那一轮时不用它，那一轮的模型不变。{@code skills} 是文字里点名的技能（{@code /name}），按出现顺序，提交的那一刻读好。
 * {@code attached} 是插件在提交时附的内容，按插件的顺序。{@code delivery} 是会话正忙时怎么送。
 * 会话的目录、是不是“不进记忆”不在这里，在 {@link SessionCreated}。
 */
public record UserPrompt(SubmissionId submission, RequestId request, String text, List<AttachmentRef> attachments,
                         ModelChoice model, List<SkillUse> skills, List<AttachedBlock> attached, Delivery delivery)
        implements Submitted, Input {

    public static final String TYPE = "UserPrompt";

    public UserPrompt {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        skills = skills == null ? List.of() : List.copyOf(skills);
        attached = attached == null ? List.of() : List.copyOf(attached);
        Objects.requireNonNull(delivery, "delivery");
    }

    @Override
    public Kind kind() {
        return Kind.PROMPT;
    }

    @Override
    public Root root() {
        return Root.USER;
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
