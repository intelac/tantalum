package tantalum.shell.loop;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;

/**
 * 按 {@code (用户, requestId)} 原子去重。内存只占位最近的提交，未命中时查索引库，
 * 所以重启后或很久以前的重复提交也不会重复执行，全部历史也不用常驻内存。
 */
public final class SubmissionLedger {

    public static final int DEFAULT_CAPACITY = 100_000;

    /** 认领结果。{@code isNew} 为 false 时不要再提交。 */
    public record Claim(SubmissionId submission, boolean isNew) {}

    /** 查已持久化的提交。 */
    @FunctionalInterface
    public interface History {
        Optional<SubmissionId> find(Principal principal, RequestId request);
    }

    private record Key(Principal principal, RequestId request) {}

    private final History history;
    private final Map<Key, SubmissionId> recent;

    public SubmissionLedger() {
        this((principal, request) -> Optional.empty(), DEFAULT_CAPACITY);
    }

    public SubmissionLedger(History history, int capacity) {
        this.history = history;
        this.recent = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, SubmissionId> eldest) {
                return size() > capacity;
            }
        };
    }

    /** 查索引不持锁，慢查询不会拖住其他提交；并发的相同请求最后在锁里只有一个胜出。 */
    public Claim claim(Principal principal, RequestId request) {
        Key key = new Key(principal, request);
        synchronized (recent) {
            SubmissionId existing = recent.get(key);
            if (existing != null) {
                return new Claim(existing, false);
            }
        }
        Optional<SubmissionId> stored = history.find(principal, request);
        synchronized (recent) {
            SubmissionId existing = recent.get(key);
            if (existing != null) {
                return new Claim(existing, false);
            }
            if (stored.isPresent()) {
                recent.put(key, stored.get());
                return new Claim(stored.get(), false);
            }
            SubmissionId fresh = new SubmissionId(UUID.randomUUID().toString());
            recent.put(key, fresh);
            return new Claim(fresh, true);
        }
    }

    /** 事件没能进入环时撤销认领，让调用方可以重试。 */
    public void release(Principal principal, RequestId request) {
        synchronized (recent) {
            recent.remove(new Key(principal, request));
        }
    }
}
