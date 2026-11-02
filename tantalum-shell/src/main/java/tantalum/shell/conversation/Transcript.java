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
    }

    /** 一个会话的全部条目和它们截至的事件序号：序号更大的 SSE 推送比快照新，反之快照更新。 */
    public record View(long seq, List<TranscriptItem> items) {}

    /** 一个会话的完整内部状态，可以写进快照，恢复后接着处理事件和没中断时相同。 */
    public record Saved(List<TranscriptItem> items, List<String> queued, Map<String, String> stepByCall, int seenMessages, long lastSeq) {

        public Saved {
            items = List.copyOf(items);
            queued = List.copyOf(queued);
            stepByCall = Map.copyOf(stepByCall);
        }
    }

    private final Map<SessionId, Conversation> sessions = new ConcurrentHashMap<>();

    /** 没有该会话时返回空的状态。 */
    @Override
    public Saved export(SessionId session) {
        Conversation conversation = sessions.get(session);
        if (conversation == null) {
            return new Saved(List.of(), List.of(), Map.of(), 0, -1);
        }
        synchronized (conversation) {
            return new Saved(List.copyOf(conversation.items.values()), List.copyOf(conversation.queued), conversation.stepByCall,
                    conversation.seenMessages, conversation.lastSeq);
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
            return new View(-1, List.of());
        }
        synchronized (conversation) {
            return new View(conversation.lastSeq, List.copyOf(conversation.items.values()));
        }
    }

    public Set<SessionId> sessions() {
        return Set.copyOf(sessions.keySet());
    }

    /** 内核处理完一个事件后调用。返回这次变化的条目，按变化先后排列；同一条目改了几次只返回最终版本。 */
    @Override
    public List<TranscriptItem> apply(EventEnvelope envelope, Decision decision) {
        Conversation conversation = sessions.computeIfAbsent(envelope.session(), key -> new Conversation());
        Map<String, TranscriptItem> changed = new LinkedHashMap<>();
        synchronized (conversation) {
            new TranscriptPass(conversation, envelope, decision, changed).run();
            conversation.lastSeq = envelope.seq();
        }
        return List.copyOf(changed.values());
    }
}
