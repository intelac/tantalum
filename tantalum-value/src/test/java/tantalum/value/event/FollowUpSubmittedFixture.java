package tantalum.value.event;

import java.util.List;
import static tantalum.value.event.FollowUpSubmitted.*;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.event.FollowUpSubmitted.*;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.ModelChoice;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class FollowUpSubmittedFixture {

    private FollowUpSubmittedFixture() {}

    public static FollowUpSubmitted of(SubmissionId submission, RequestId request, String text) {
        return new FollowUpSubmitted(submission, request, text, List.of(), null, List.of());
    }

    public static FollowUpSubmitted of(SubmissionId submission, RequestId request, String text, List<AttachmentRef> attachments) {
        return new FollowUpSubmitted(submission, request, text, attachments, null, List.of());
    }

    public static FollowUpSubmitted of(SubmissionId submission, RequestId request, String text, List<AttachmentRef> attachments, ModelChoice model) {
        return new FollowUpSubmitted(submission, request, text, attachments, model, List.of());
    }
}
