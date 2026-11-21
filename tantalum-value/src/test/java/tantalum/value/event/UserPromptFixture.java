package tantalum.value.event;

import java.util.List;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.ModelChoice;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。{@code of} 是排队的，{@code join} 是并进正在跑的那一轮的。 */
public final class UserPromptFixture {
    private UserPromptFixture() {}

    public static UserPrompt of(SubmissionId submission, RequestId request, String text) {
        return new UserPrompt(submission, request, text, List.of(), null, List.of(), List.of(), Delivery.QUEUE);
    }

    public static UserPrompt of(SubmissionId submission, RequestId request, String text, List<AttachmentRef> attachments) {
        return new UserPrompt(submission, request, text, attachments, null, List.of(), List.of(), Delivery.QUEUE);
    }

    public static UserPrompt of(SubmissionId submission, RequestId request, String text, List<AttachmentRef> attachments, ModelChoice model) {
        return new UserPrompt(submission, request, text, attachments, model, List.of(), List.of(), Delivery.QUEUE);
    }

    public static UserPrompt join(SubmissionId submission, RequestId request, String text) {
        return new UserPrompt(submission, request, text, List.of(), null, List.of(), List.of(), Delivery.JOIN);
    }

    public static UserPrompt join(SubmissionId submission, RequestId request, String text, List<AttachmentRef> attachments) {
        return new UserPrompt(submission, request, text, attachments, null, List.of(), List.of(), Delivery.JOIN);
    }
}
