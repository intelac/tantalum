package tantalum.value.decision;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.model.Message;
import tantalum.value.state.Compaction;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionState;
import tantalum.value.state.UsageAnchor;

/**
 * 一步里一个会话的变化。{@code messages} 只追加，所以只记新增的；其余字段为 null 表示没变，
 * {@link Changed} 里的值可以是 null（变成 null）。{@code created} 时以 {@link SessionState#start} 为底。
 */
public record SessionChange(SessionId id, Principal principal, boolean created, List<Message> appended,
                            Changed<RunId> activeRun, Changed<RunId> lastRun, Changed<List<Message>> followUps,
                            Changed<SessionContext> context, Changed<Compaction> compaction,
                            Changed<UsageAnchor> anchor, Changed<List<String>> omittedImages) {

    /** 一个变了的字段的新值。 */
    public record Changed<T>(T value) {}

    public SessionChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(principal, "principal");
        appended = List.copyOf(appended);
    }

    /** 比较同一个会话处理前后的状态。{@code before} 为 null 表示这一步新建了它。消息不是只追加时抛出。 */
    public static SessionChange between(SessionState before, SessionState after) {
        SessionState base = before == null ? SessionState.start(after.id(), after.principal()) : before;
        if (!base.principal().equals(after.principal())) {
            throw new IllegalStateException("session " + after.id().value() + " changed its principal");
        }
        List<Message> old = base.messages();
        List<Message> now = after.messages();
        if (!startsWith(now, old)) {
            throw new IllegalStateException("session " + after.id().value() + " rewrote its history instead of appending");
        }
        return new SessionChange(after.id(), after.principal(), before == null, now.subList(old.size(), now.size()),
                changed(base.activeRun(), after.activeRun()), changed(base.lastRun(), after.lastRun()),
                changed(base.followUps(), after.followUps()), changed(base.context(), after.context()),
                changed(base.compaction(), after.compaction()), changed(base.anchor(), after.anchor()),
                changed(base.omittedImages(), after.omittedImages()));
    }

    /** 套到处理前的状态上。{@code before} 为 null 时以新会话为底。 */
    public SessionState applyTo(SessionState before) {
        SessionState base = before == null ? SessionState.start(id, principal) : before;
        List<Message> messages = base.messages();
        if (!appended.isEmpty()) {
            List<Message> grown = new ArrayList<>(messages);
            grown.addAll(appended);
            messages = grown;
        }
        return new SessionState(id, principal, messages,
                pick(activeRun, base.activeRun()), pick(lastRun, base.lastRun()), pick(followUps, base.followUps()),
                pick(context, base.context()), pick(compaction, base.compaction()), pick(anchor, base.anchor()),
                pick(omittedImages, base.omittedImages()));
    }

    /** 没有任何变化：可以不写。 */
    public boolean isEmpty() {
        return !created && appended.isEmpty() && activeRun == null && lastRun == null && followUps == null
                && context == null && compaction == null && anchor == null && omittedImages == null;
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
