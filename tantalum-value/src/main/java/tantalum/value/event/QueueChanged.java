package tantalum.value.event;

import java.util.Objects;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;

/**
 * 改收件队列里你自己排的一条。{@code item} 是那一条的编号（{@link tantalum.value.state.InboxItem#id}）；找不到、或者那一条不是你说的，
 * 什么都不改。{@code text} 只在 {@code EDIT} 时有，是新的文字。
 */
public record QueueChanged(SubmissionId submission, RequestId request, Action action, long item, String text) implements Submitted {

    /** 改文字、拿掉、改成并进正在跑的这一轮。 */
    public enum Action { EDIT, REMOVE, JOIN }

    public static final String TYPE = "QueueChanged";

    public QueueChanged {
        Objects.requireNonNull(action, "action");
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
