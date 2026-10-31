package tantalum.shell.sse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import tantalum.shell.conversation.ItemJson;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.shell.loop.FactListener;
import tantalum.value.decision.Fact;
import tantalum.value.id.Principal;
import tantalum.value.id.RunRef;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 按用户推送事实、条目变化和分片。发布方绝不阻塞：订阅者的队列满了就断开它，它重连后补读。
 * 近期消息留在内存；超出范围时从 journal 回放。
 */
public final class SseHub implements FactListener {

    /** 从 journal 回放某用户在某位置之后的事实。 */
    @FunctionalInterface
    public interface History {
        List<SseMessage> after(Principal principal, String lastEventId);
    }

    /** 一个订阅。消费方循环 {@link #poll}，用完 {@link #close}。 */
    public final class Subscription implements AutoCloseable {
        private final Principal principal;
        private final BlockingQueue<SseMessage> queue;
        /** 补读的内容，先于新消息发出。不占队列容量，否则落后太多时会断开、重连、再断开。 */
        private final Queue<SseMessage> catchUp = new ConcurrentLinkedQueue<>();
        private volatile boolean closed;

        private Subscription(Principal principal, int capacity) {
            this.principal = principal;
            this.queue = new ArrayBlockingQueue<>(capacity);
        }

        public SseMessage poll(long timeout, TimeUnit unit) throws InterruptedException {
            SseMessage missed = catchUp.poll();
            if (missed != null) {
                return missed;
            }
            SseMessage next = queue.poll(timeout, unit);
            return next == WAKE_UP ? null : next;
        }

        public boolean closed() {
            return closed;
        }

        private void offer(SseMessage message) {
            if (!queue.offer(message)) {
                close();
            }
        }

        @Override
        public void close() {
            closed = true;
            subscribers(principal).remove(this);
            queue.offer(WAKE_UP);
        }
    }

    /** 让正在等待的消费方立刻醒来，发现订阅已关闭，不用等到下一次心跳。 */
    private static final SseMessage WAKE_UP = new SseMessage(null, "", "");

    private record Stored(long seq, int index, Principal owner, SseMessage message) {}

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final int queueCapacity;
    private final int recentCapacity;
    private final History history;
    /** 序列化条目用，要能处理 {@code Json} 和内容块。 */
    private final JsonMapper itemMapper;
    private final Map<Principal, Set<Subscription>> subscribers = new ConcurrentHashMap<>();
    private final Deque<Stored> recent = new ArrayDeque<>();
    /** 内存能覆盖的最早序号，更早的要回放。 */
    private long coveredFromSeq;

    public SseHub(long firstLiveSeq, int queueCapacity, int recentCapacity, History history, JsonMapper itemMapper) {
        this.coveredFromSeq = firstLiveSeq;
        this.queueCapacity = queueCapacity;
        this.recentCapacity = recentCapacity;
        this.history = history;
        this.itemMapper = itemMapper;
    }

    private Set<Subscription> subscribers(Principal principal) {
        return subscribers.computeIfAbsent(principal, key -> ConcurrentHashMap.newKeySet());
    }

    /** {@code lastEventId} 为空或无法解析时只接收之后的消息。 */
    public Subscription subscribe(Principal principal, String lastEventId) {
        Subscription subscription = new Subscription(principal, queueCapacity);
        long[] position = parse(lastEventId);
        List<SseMessage> missed = new ArrayList<>();
        boolean fromHistory = false;
        synchronized (recent) {
            if (position != null && position[0] < coveredFromSeq - 1) {
                fromHistory = true;
            } else if (position != null) {
                for (Stored stored : recent) {
                    boolean later = stored.seq() > position[0]
                            || (stored.seq() == position[0] && stored.index() > position[1]);
                    if (later && stored.owner().equals(principal)) {
                        missed.add(stored.message());
                    }
                }
            }
            subscribers(principal).add(subscription);
        }
        if (fromHistory) {
            missed = history.after(principal, lastEventId);
        }
        subscription.catchUp.addAll(missed);
        return subscription;
    }

    /** 服务关闭时调用，否则要等每条连接超时。 */
    public void closeEveryone() {
        subscribers.keySet().forEach(this::closeAll);
    }

    /** 用户退出或被停用时调用，断开它的全部连接。 */
    public void closeAll(Principal principal) {
        List.copyOf(subscribers(principal)).forEach(Subscription::close);
    }

    private static long[] parse(String id) {
        if (id == null) {
            return null;
        }
        String[] parts = id.split("\\.");
        try {
            return parts.length == 2 ? new long[] {Long.parseLong(parts[0]), Long.parseLong(parts[1])} : null;
        } catch (NumberFormatException garbage) {
            return null;
        }
    }

    @Override
    public void onFact(long seq, int index, Fact fact) {
        publish(seq, index, fact.run().principal(), toMessage(seq, index, fact));
    }

    @Override
    public void onItem(long seq, int index, Principal owner, TranscriptItem item) {
        publish(seq, index, owner, toMessage(itemMapper, seq, index, item));
    }

    private void publish(long seq, int index, Principal owner, SseMessage message) {
        synchronized (recent) {
            recent.addLast(new Stored(seq, index, owner, message));
            if (recent.size() > recentCapacity) {
                coveredFromSeq = recent.removeFirst().seq() + 1;
            }
            subscribers(owner).forEach(subscription -> subscription.offer(message));
        }
    }

    public void onChunk(RunRef run, String kind, String text) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run", run.run().value());
        data.put("session", run.session().value());
        data.put("kind", kind);
        data.put("text", text);
        SseMessage message = new SseMessage(null, "chunk", MAPPER.writeValueAsString(data));
        subscribers(run.principal()).forEach(subscription -> subscription.offer(message));
    }

    /** 把条目变化转成 {@code item} 消息：完整条目，客户端按 ID 覆盖。回放补读时也用它。 */
    public static SseMessage toMessage(JsonMapper mapper, long seq, int index, TranscriptItem item) {
        return new SseMessage(seq + "." + index, "item", mapper.writeValueAsString(ItemJson.toMap(mapper, item)));
    }

    /** 把事实转成消息，回放补读时也用它。 */
    public static SseMessage toMessage(long seq, int index, Fact fact) {
        Map<String, Object> data = new HashMap<>(MAPPER.convertValue(fact, new TypeReference<Map<String, Object>>() { }));
        data.remove("run");
        data.put("run", fact.run().run().value());
        data.put("session", fact.run().session().value());
        return new SseMessage(seq + "." + index, fact.type(), MAPPER.writeValueAsString(data));
    }
}
