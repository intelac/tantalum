package tantalum.value.decision;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tantalum.value.assignment.Assignments;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;
import tantalum.value.state.Compaction;
import tantalum.value.state.HelperOf;
import tantalum.value.state.InboxItem;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionKind;
import tantalum.value.state.SessionState;
import tantalum.value.state.UsageAnchor;

/**
 * 一步里一个会话的变化。{@code messages} 只追加，所以只记新增的；{@code windowStart} 变了表示这一步压缩完成、去掉了它之前的消息
 * （只能去掉这一步开始时就有的）；其余字段为 null 表示没变，
 * {@link Changed} 里的值可以是 null（变成 null）。{@code created} 时这一步新建了它：种类、帮手的出身、目录、是不是不进记忆都在这一行里。
 */
public record SessionChange(SessionId id, Principal principal, boolean created, SessionKind kind, HelperOf helper, String dir,
                            boolean offRecord, List<Message> appended, Changed<Integer> windowStart,
                            Changed<RunId> activeRun, Changed<RunId> lastRun, Changed<List<InboxItem>> inbox,
                            Changed<SessionContext> context, Changed<Compaction> compaction,
                            Changed<UsageAnchor> anchor, Changed<List<String>> omittedImages, Changed<List<String>> toolsSeen,
                            Changed<Map<String, Json.Obj>> notes, Changed<ModelChoice> model, Changed<Assignments> assignments,
                            Changed<List<tantalum.value.state.Withdrawal>> withdrawn,
                            Changed<List<tantalum.value.delivery.Outgoing>> outbox) {

    /** 一个变了的字段的新值。 */
    public record Changed<T>(T value) {}

    public SessionChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(principal, "principal");
        appended = List.copyOf(appended);
        if (created != (kind != null)) {
            throw new IllegalArgumentException("exactly the step that creates a session records its kind");
        }
    }

    /**
     * 比较同一个会话处理前后的状态。{@code before} 为 null 表示这一步新建了它。消息不是只追加时抛出；
     * 建好不变的四样（种类、帮手的出身、目录、是不是不进记忆）变了也抛出。
     */
    public static SessionChange between(SessionState before, SessionState after) {
        SessionState base = before == null
                ? SessionState.start(after.id(), after.principal(), after.kind(), after.helper(), after.dir(), after.offRecord()) : before;
        if (!base.principal().equals(after.principal())) {
            throw new IllegalStateException("session " + after.id().value() + " changed its principal");
        }
        if (base.kind() != after.kind() || !Objects.equals(base.helper(), after.helper()) || !Objects.equals(base.dir(), after.dir())
                || base.offRecord() != after.offRecord()) {
            throw new IllegalStateException("session " + after.id().value() + " changed what is fixed when it is created");
        }
        // 去掉的只能是这一步开始时就有的那一段的开头；之后的消息照旧只追加
        int dropped = after.windowStart() - base.windowStart();
        if (dropped < 0 || dropped > base.messages().size()) {
            throw new IllegalStateException("session " + after.id().value() + " moved its window from " + base.windowStart() + " to "
                    + after.windowStart() + " with " + base.messages().size() + " messages");
        }
        List<Message> old = base.messages().subList(dropped, base.messages().size());
        List<Message> now = after.messages();
        if (!startsWith(now, old)) {
            throw new IllegalStateException("session " + after.id().value() + " rewrote its history instead of appending");
        }
        boolean created = before == null;
        return new SessionChange(after.id(), after.principal(), created, created ? after.kind() : null, created ? after.helper() : null,
                created ? after.dir() : null, created && after.offRecord(), now.subList(old.size(), now.size()),
                dropped == 0 ? null : new Changed<>(after.windowStart()),
                changed(base.activeRun(), after.activeRun()), changed(base.lastRun(), after.lastRun()),
                changed(base.inbox(), after.inbox()), changed(base.context(), after.context()),
                changed(base.compaction(), after.compaction()), changed(base.anchor(), after.anchor()),
                changed(base.omittedImages(), after.omittedImages()), changed(base.toolsSeen(), after.toolsSeen()),
                changed(base.notes(), after.notes()), changed(base.model(), after.model()),
                changed(base.assignments(), after.assignments()), changed(base.withdrawn(), after.withdrawn()),
                changed(base.outbox(), after.outbox()));
    }

    /** 套到处理前的状态上。这一步新建会话时 {@code before} 为 null。 */
    public SessionState applyTo(SessionState before) {
        if (created != (before == null)) {
            throw new IllegalStateException("session " + id.value() + (created ? " is created twice" : " changes before it is created"));
        }
        SessionState base = before == null ? SessionState.start(id, principal, kind, helper, dir, offRecord) : before;
        List<Message> messages = base.messages();
        int start = base.windowStart();
        if (windowStart != null) {
            int dropped = windowStart.value() - start;
            if (dropped < 0 || dropped > messages.size()) {
                throw new IllegalStateException("session " + id.value() + " moves its window from " + start + " to " + windowStart.value()
                        + " with " + messages.size() + " messages");
            }
            messages = messages.subList(dropped, messages.size());
            start = windowStart.value();
        }
        if (!appended.isEmpty()) {
            List<Message> grown = new ArrayList<>(messages);
            grown.addAll(appended);
            messages = grown;
        }
        return new SessionState(id, principal, base.kind(), base.helper(), messages, start,
                pick(activeRun, base.activeRun()), pick(lastRun, base.lastRun()), pick(inbox, base.inbox()),
                pick(context, base.context()), pick(compaction, base.compaction()), pick(anchor, base.anchor()),
                pick(omittedImages, base.omittedImages()), pick(toolsSeen, base.toolsSeen()), pick(notes, base.notes()),
                pick(model, base.model()), base.dir(), base.offRecord(), pick(assignments, base.assignments()),
                pick(withdrawn, base.withdrawn()), pick(outbox, base.outbox()));
    }

    /** 没有任何变化：可以不写。 */
    public boolean isEmpty() {
        return !created && appended.isEmpty() && windowStart == null && activeRun == null && lastRun == null && inbox == null
                && context == null && compaction == null && anchor == null && omittedImages == null && toolsSeen == null
                && notes == null && model == null && assignments == null && withdrawn == null && outbox == null;
    }
    /** 先比引用：内核追加时沿用原来的消息对象，长历史不用逐条深比较。 */
    private static boolean startsWith(List<Message> now, List<Message> old) {
        if (now.size() < old.size()) {
            return false;
        }
        for (int i = 0; i < old.size(); i++) {
            if (now.get(i) != old.get(i) && !now.get(i).equals(old.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static <T> Changed<T> changed(T before, T after) {
        return before == after || Objects.equals(before, after) ? null : new Changed<>(after);
    }

    private static <T> T pick(Changed<T> change, T current) {
        return change == null ? current : change.value();
    }
}
