package tantalum.value.event;

import java.util.List;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.OwnerRef;

/** 用户提问，也是运行的创建事件。{@code attachments} 按上传顺序，旧行读出来是空列表。 */
public record UserPrompt(SubmissionId submission, RequestId request, OwnerRef owner, String text,
                         List<AttachmentRef> attachments) implements Submitted {

    public static final String TYPE = "UserPrompt";

    public UserPrompt {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public UserPrompt(SubmissionId submission, RequestId request, OwnerRef owner, String text) {
        this(submission, request, owner, text, List.of());
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
