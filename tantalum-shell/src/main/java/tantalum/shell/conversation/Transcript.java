package tantalum.shell.conversation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import tantalum.value.decision.Decision;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.SessionId;
import tantalum.value.state.KernelState;

/**
 * 事件到对话条目的增量投影，按会话保存。输入是内核处理完一个事件后的 {@code (envelope, decision)}，
 * 实时和回放走同一个函数，所以结果相同。只改内存，不做 I/O。
 */
public final class Transcript implements Projection {

    /** 一个会话的条目和为了增量更新记住的少量位置。 */
    static final class Conversation {
        final Map<String, TranscriptItem> items = new LinkedHashMap<>();
        /** 排队中的用户消息 ID，运行开始时按先后取出。 */
        final Deque<String> queued = new ArrayDeque<>();
        /** 工具调用 ID 到步骤条目 ID。 */
        final Map<String, String> stepByCall = new HashMap<>();
        /** 已看过的会话消息数，用来发现这次新追加的消息。 */
        int seenMessages;
        /** 这个会话最后投影过的事件序号；快照带上它，客户端据此和 SSE 推送合并。 */
        long lastSeq = -1;
        /** 去掉了前面几个条目：{@code items} 里第一个的位置（live-views）。 */
        int offset;
        /** 第一条提问（撤下时跟着换），会话标题用它；去掉了也还在这里。 */
        TranscriptItem.UserMessage firstUser;
        /** 全部条目里最大的时间（运行状态按更新时间），会话列表的最近活动。 */
        java.time.Instant latest = java.time.Instant.EPOCH;
    }

    /**
     * 一个会话留在内存的条目（从位置 {@code offset} 起的连续一段）和它们截至的事件序号：序号更大的 SSE 推送比快照新，反之快照更新。
     * {@code firstUser}、{@code latest} 是全部条目的（标题、最近活动）。
     */
    public record View(long seq, int offset, List<TranscriptItem> items, TranscriptItem.UserMessage firstUser, java.time.Instant latest) {

        public View {
            items = List.copyOf(items);
            latest = latest == null ? java.time.Instant.EPOCH : latest;
        }
    }

    /** 一个会话的完整内部状态，重放会话文件得到，恢复后接着处理事件和没中断时相同。 */
    public record Saved(List<TranscriptItem> items, List<String> queued, Map<String, String> stepByCall, int seenMessages, long lastSeq,
                        int offset, TranscriptItem.UserMessage firstUser, java.time.Instant latest) {

        /** 只读查询用的样子。 */
        public View view() {
            return new View(lastSeq, offset, items, firstUser, latest);
        }

        public Saved {
            items = List.copyOf(items);
            latest = latest == null ? java.time.Instant.EPOCH : latest;
            queued = List.copyOf(queued);
            stepByCall = Map.copyOf(stepByCall);
        }
    }

    private final Map<SessionId, Conversation> sessions = new ConcurrentHashMap<>();
    /** 全留（不去掉前面的条目）：往前翻过切点时重放账、等价测试用。 */
    private boolean everything;

    /** 从现在起全部条目都留在内存，不按切点去掉（live-views 之前的样子）。 */
    public Transcript keepingEverything() {
        everything = true;
        return this;
    }

    /** 没有该会话时返回空的状态。 */
    public Saved export(SessionId session) {
        Conversation conversation = sessions.get(session);
        if (conversation == null) {
            return new Saved(List.of(), List.of(), Map.of(), 0, -1, 0, null, null);
        }
        synchronized (conversation) {
            return new Saved(List.copyOf(conversation.items.values()), List.copyOf(conversation.queued), conversation.stepByCall,
                    conversation.seenMessages, conversation.lastSeq, conversation.offset, conversation.firstUser, conversation.latest);
        }
    }

    @Override
    public void restore(SessionId session, Saved saved) {
        Conversation conversation = new Conversation();
        saved.items().forEach(item -> conversation.items.put(item.id(), item));
        conversation.queued.addAll(saved.queued());
        conversation.stepByCall.putAll(saved.stepByCall());
        conversation.seenMessages = saved.seenMessages();
        conversation.lastSeq = saved.lastSeq();
        conversation.offset = saved.offset();
        conversation.firstUser = saved.firstUser();
        conversation.latest = saved.latest();
        sessions.put(session, conversation);
    }

    @Override
    public void remove(SessionId session) {
        sessions.remove(session);
    }

    /** 没有该会话时返回空列表。 */
    public List<TranscriptItem> items(SessionId session) {
        return view(session).items();
    }

    /** 条目和序号在同一把锁里读出，所以彼此一致。没有该会话时序号为 -1。 */
    public View view(SessionId session) {
        Conversation conversation = sessions.get(session);
        if (conversation == null) {
            return new View(-1, 0, List.of(), null, null);
        }
        synchronized (conversation) {
            return new View(conversation.lastSeq, conversation.offset, List.copyOf(conversation.items.values()), conversation.firstUser,
                    conversation.latest);
        }
    }

    public Set<SessionId> sessions() {
        return Set.copyOf(sessions.keySet());
    }

    /** 内核处理完一个事件后调用。返回这次变化的条目，按变化先后排列；同一条目改了几次只返回最终版本。 */
    @Override
    public List<TranscriptItem> apply(EventEnvelope envelope, Decision decision) {
        return apply(envelope, null, decision);
    }

    /** 同上，{@code before} 是处理前的状态，用来找出这一步追加的工具变化；为 null 时不出工具变化条目。 */
    @Override
    public List<TranscriptItem> apply(EventEnvelope envelope, KernelState before, Decision decision) {
        Conversation conversation = sessions.computeIfAbsent(envelope.session(), key -> new Conversation());
        Map<String, TranscriptItem> changed = new LinkedHashMap<>();
        synchronized (conversation) {
            if (!Projection.forgotten(envelope, before, decision)) {
                new TranscriptPass(conversation, envelope, before, decision, changed).run();
            }
            conversation.lastSeq = envelope.seq();
            for (TranscriptItem item : changed.values()) {
                if (item instanceof TranscriptItem.UserMessage message && (conversation.firstUser == null || conversation.firstUser.id().equals(message.id()))) {
                    conversation.firstUser = message;
                }
                java.time.Instant at = item instanceof TranscriptItem.RunStatus status ? status.updatedAt() : item.at();
                if (at.isAfter(conversation.latest)) {
                    conversation.latest = at;
                }
            }
            if (!everything) {
                Tail.trim(conversation, decision.newState().sessions().get(envelope.session()));
            }
        }
        return List.copyOf(changed.values());
    }
}
