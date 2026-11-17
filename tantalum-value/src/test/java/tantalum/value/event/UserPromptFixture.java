package tantalum.value.event;

import java.util.List;
import static tantalum.value.event.UserPrompt.*;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.event.UserPrompt.*;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.ModelChoice;
import tantalum.value.state.OwnerRef;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class UserPromptFixture {

    private UserPromptFixture() {}

    public static UserPrompt of(SubmissionId submission, RequestId request, OwnerRef owner, String text) {
        return new UserPrompt(submission, request, owner, text, List.of(), null, null, List.of());
    }

    public static UserPrompt of(SubmissionId submission, RequestId request, OwnerRef owner, String text, List<AttachmentRef> attachments) {
        return new UserPrompt(submission, request, owner, text, attachments, null, null, List.of());
    }

    public static UserPrompt of(SubmissionId submission, RequestId request, OwnerRef owner, String text, List<AttachmentRef> attachments, ModelChoice model) {
        return new UserPrompt(submission, request, owner, text, attachments, model, null, List.of());
    }

    public static UserPrompt of(SubmissionId submission, RequestId request, OwnerRef owner, String text, List<AttachmentRef> attachments, ModelChoice model, String dir) {
        return new UserPrompt(submission, request, owner, text, attachments, model, dir, List.of());
    }
}
