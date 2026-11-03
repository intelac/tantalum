package tantalum.value.event;

import java.util.List;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;

/** 排队提交，运行结束后开始下一轮。{@code attachments} 按上传顺序，旧行读出来是空列表。 */
public record FollowUpSubmitted(SubmissionId submission, RequestId request, String text, List<AttachmentRef> attachments)
        implements Submitted {

    public static final String TYPE = "FollowUpSubmitted";

    public FollowUpSubmitted {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public FollowUpSubmitted(SubmissionId submission, RequestId request, String text) {
        this(submission, request, text, List.of());
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
