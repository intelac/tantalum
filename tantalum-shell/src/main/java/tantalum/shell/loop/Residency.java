package tantalum.shell.loop;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import tantalum.value.id.SessionId;

/**
 * 每个会话是否在内存，以及有多少事件还没过 cleanroom。通道判断前加一，cleanroom 处理完减一；
 * 只有计数为零才能回收，所以回收和载入不会竞争。不在内存又没有在途事件的会话没有条目，表不随历史增长。
 */
public final class Residency {

    /** 通道看到的会话状态。 */
    public enum State { COLD, RESIDENT, FAILED }

    private record Entry(int pins, State state, String failure) {

        Entry pinned(int delta) {
            return new Entry(pins + delta, state, failure);
        }

        /** 不在内存又没有在途事件时不留条目。 */
        Entry orNone() {
            return pins == 0 && state == State.COLD ? null : this;
        }
    }

    private final Map<SessionId, Entry> entries = new ConcurrentHashMap<>();

    /** 钉住一次，返回钉住前的状态。 */
    public State pin(SessionId session) {
        AtomicReference<State> seen = new AtomicReference<>();
        entries.compute(session, (key, entry) -> {
            Entry current = entry == null ? new Entry(0, State.COLD, null) : entry;
            seen.set(current.state());
            return current.pinned(1);
        });
        return seen.get();
    }

    public void unpin(SessionId session) {
        entries.computeIfPresent(session, (key, entry) -> entry.pins() == 0 ? entry.orNone() : entry.pinned(-1).orNone());
    }

    public void resident(SessionId session) {
        entries.compute(session, (key, entry) -> new Entry(entry == null ? 0 : entry.pins(), State.RESIDENT, null));
    }

    /** 载入失败：这个会话到下次重启前都拒绝，避免半截状态被当成新会话。 */
    public void fail(SessionId session, String reason) {
        entries.compute(session, (key, entry) -> new Entry(entry == null ? 0 : entry.pins(), State.FAILED, reason));
    }

    /** 载入失败的原因，没有失败时为 null。 */
    public String failure(SessionId session) {
        Entry entry = entries.get(session);
        return entry == null ? null : entry.failure();
    }

    /** 钉住为零才成功，成功后下一个事件会看到 {@link State#COLD}。 */
    public boolean evict(SessionId session) {
        AtomicBoolean evicted = new AtomicBoolean();
        entries.computeIfPresent(session, (key, entry) -> {
            if (entry.pins() != 0 || entry.state() != State.RESIDENT) {
                return entry;
            }
            evicted.set(true);
            return null;
        });
        return evicted.get();
    }

    public int pins(SessionId session) {
        Entry entry = entries.get(session);
        return entry == null ? 0 : entry.pins();
    }
}
