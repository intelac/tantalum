package tantalum.value.state;

import tantalum.value.id.SessionId;

import java.util.Objects;
import tantalum.value.event.Input;
import tantalum.value.event.Root;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;

/**
 * 收件队列里的一条：一条到了、还没被模型看到的输入。{@code id} 是把它放进队列的那个事件的序号，改队列按它找。
 * {@code message} 是它进历史时的样子；{@code model} 是它自己带的模型选择（没带为 null），为它开一轮时用。
 * {@code join} 为真表示它可以并进正在跑的这一轮：下一次调模型前并进历史，而且那一轮要为它再调一次模型。
 * {@code assignment} 只有交代和结果有：交代是它自己的编号，结果是它回答的那次交代的编号。
 */
public record InboxItem(long id, Input.Kind kind, Root root, Message message, ModelChoice model, boolean join, String assignment,
                        SessionId from) {

    public InboxItem {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(message, "message");
        if ((kind == Input.Kind.PROMPT || kind == Input.Kind.NOTICE) != (assignment == null)) {
            throw new IllegalArgumentException("exactly an assignment or a report names its assignment");
        }
        if (kind == Input.Kind.NOTICE && join) {
            throw new IllegalArgumentException("a notice is carried along, it never forces another model call");
        }
    }

    public InboxItem withMessage(Message newMessage) {
        return new InboxItem(id, kind, root, newMessage, model, join, assignment, from);
    }

    public InboxItem withJoin(boolean newJoin) {
        return new InboxItem(id, kind, root, message, model, newJoin, assignment, from);
    }
}
