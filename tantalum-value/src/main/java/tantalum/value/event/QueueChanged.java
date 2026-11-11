package tantalum.value.event;

import java.util.List;
import java.util.Objects;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;

/**
 * 改排队的提问。排队的提问没有编号，用“位置加原文”定位：{@code index} 是提交时它在队列里的位置，
 * {@code expected} 和 {@code attachments} 是它当时的文字和附件；内核从 index 往前找第一条内容相同的
 * （{@link tantalum.value.state.Queue#locate}），找不到就什么都不改。{@code text} 只在 {@code EDIT} 时有，是新的文字。
 */
public record QueueChanged(SubmissionId submission, RequestId request, Action action, int index, String expected,
                           List<AttachmentRef> attachments, String text) implements Submitted {

    /** 改文字、移除、转成插话。 */
    public enum Action { EDIT, REMOVE, STEER }

    public static final String TYPE = "QueueChanged";

    public QueueChanged {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(expected, "expected");
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        if ((text != null) != (action == Action.EDIT)) {
            throw new IllegalArgumentException("text is present exactly when the action is EDIT");
        }
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
