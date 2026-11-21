package tantalum.shell.conversation;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import tantalum.value.event.Input;
import tantalum.value.id.SessionId;
import tantalum.value.state.Ended;
import tantalum.value.state.EndReason;
import tantalum.value.state.InboxItem;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunCause;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/**
 * 手动压缩那一轮里你选了“并入”的话（审查）：那一轮不调模型，并不进去。压缩不是你停的就照常结束时，内核把它们留在队里、改成排着，
 * 队首那条当场开新的一轮；对话页和轨迹要把开这一轮的那几条挂到新的一轮上。你停的压缩：它们只进历史，不开轮，这里给空。
 * 只看状态里的事实：上一轮的起因和结束的原因、这一步离开收件队列的是哪几条。
 */
public final class CompactionJoins {

    private CompactionJoins() {}

    /** 这一步结束了一轮不是你停的手动压缩、开了新的一轮时，开这一轮的那几条（你选了并入的话）；否则为空。 */
    public static List<InboxItem> started(KernelState before, KernelState after, SessionId session) {
        SessionState earlier = before == null ? null : before.sessions().get(session);
        SessionState now = after.sessions().get(session);
        if (earlier == null || now == null || earlier.activeRun() == null || now.activeRun() == null
                || earlier.activeRun().equals(now.activeRun())) {
            return List.of();
        }
        if (!(before.runs().get(earlier.activeRun()) instanceof RunState was) || was.cause().startedBy() != RunCause.StartedBy.COMPACTION
                || !(after.runs().get(earlier.activeRun()) instanceof RunState ended) || !(ended.phase() instanceof Ended end)
                || end.reason() == EndReason.CANCELLED && end.detail() == null) {
            return List.of();
        }
        Set<Long> still = now.inbox().stream().map(InboxItem::id).collect(Collectors.toSet());
        return earlier.inbox().stream().filter(item -> item.join() && item.kind() == Input.Kind.PROMPT && !still.contains(item.id())).toList();
    }
}
