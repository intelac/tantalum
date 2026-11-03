package tantalum.value.event;

import java.util.List;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;

/** 运行中插话，在当前模型调用或工具轮之后加入。{@code attachments} 按上传顺序，旧行读出来是空列表。 */
public record SteerSubmitted(SubmissionId submission, RequestId request, String text, List<AttachmentRef> attachments)
        implements Submitted {

    public static final String TYPE = "SteerSubmitted";

    public SteerSubmitted {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public SteerSubmitted(SubmissionId submission, RequestId request, String text) {
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
