package tantalum.value.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.model.Message;

/** 一个会话。{@code messages} 是完整历史，只追加；模型看到的是 {@link #view()}。{@code activeRun} 没有运行时为空；
 * {@code lastRun} 是最近一次开始的运行，运行结束后仍保留，用来判断迟到的结果是否还能进历史。
 * {@code compaction} 是最近一次压缩的替换段，{@code anchor} 是上次回复的实测用量，两者都可为空。 */
public record SessionState(SessionId id, Principal principal, List<Message> messages,
                           RunId activeRun, RunId lastRun, List<String> followUps, SessionContext context,
                           Compaction compaction, UsageAnchor anchor) {

    public SessionState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(principal, "principal");
        messages = List.copyOf(messages);
        followUps = List.copyOf(followUps);
        if (compaction != null && compaction.tailFrom() > messages.size()) {
            throw new IllegalArgumentException("tailFrom " + compaction.tailFrom() + " beyond " + messages.size() + " messages");
        }
    }

    public static SessionState start(SessionId id, Principal principal) {
        return new SessionState(id, principal, List.of(), null, null, List.of(), null, null, null);
    }

    /** 模型看到的消息：压缩过就是替换段加上之后追加的，否则就是全部历史。 */
    public List<Message> view() {
        if (compaction == null) {
            return messages;
        }
        List<Message> view = new ArrayList<>(compaction.replacement());
        view.addAll(messages.subList(compaction.tailFrom(), messages.size()));
        return List.copyOf(view);
    }

    public SessionState append(Message message) {
        List<Message> updated = new ArrayList<>(messages);
        updated.add(message);
        return new SessionState(id, principal, updated, activeRun, lastRun, followUps, context, compaction, anchor);
    }

    /** 开始运行时传入运行 ID，同时记为 {@code lastRun}；结束时传 null 只清 {@code activeRun}。 */
    public SessionState withActiveRun(RunId run) {
        return new SessionState(id, principal, messages, run, run == null ? lastRun : run, followUps, context, compaction, anchor);
    }

    public SessionState withFollowUps(List<String> newFollowUps) {
        return new SessionState(id, principal, messages, activeRun, lastRun, newFollowUps, context, compaction, anchor);
    }

    public SessionState withContext(SessionContext newContext) {
        return new SessionState(id, principal, messages, activeRun, lastRun, followUps, newContext, compaction, anchor);
    }

    /** 压缩成功：换替换段，锚点作废。 */
    public SessionState compacted(Compaction newCompaction) {
        return new SessionState(id, principal, messages, activeRun, lastRun, followUps, context, newCompaction, null);
    }

    public SessionState withAnchor(UsageAnchor newAnchor) {
        return new SessionState(id, principal, messages, activeRun, lastRun, followUps, context, compaction, newAnchor);
    }

    /** 该运行是否仍是会话历史的末尾：它正在进行，或它结束后没有开始过新运行。 */
    public boolean isLatestRun(RunId run) {
        return run != null && run.equals(lastRun);
    }
}
