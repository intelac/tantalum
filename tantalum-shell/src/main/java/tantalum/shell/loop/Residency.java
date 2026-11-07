package tantalum.shell.loop;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import tantalum.value.id.SessionId;

/**
 * 每个会话是否在内存，以及有多少事件还没过 cleanroom。通道判断前加一，cleanroom 处理完减一；
 * 只有计数为零才能回收，所以回收和载入不会竞争。不在内存又没有在途事件的会话没有条目，表不随历史增长。
 * 载入失败的会话在 {@code retry} 内拒绝（半截状态不能当新会话），过了这段时间下一个事件再试。
 */
public final class Residency {

    /** 通道看到的会话状态。 */
    public enum State { COLD, RESIDENT, FAILED }

    /** 钉住时看到的状态和（失败时的）原因：一次读出，通道不用再读第二次时钟。 */
    public record Pinned(State state, String failure) {}

    private record Entry(int pins, State state, String failure, long failedAt) {

        Entry pinned(int delta) {
            return new Entry(pins + delta, state, failure, failedAt);
        }

        /** 不在内存又没有在途事件时不留条目；失败已过重试时间的也不留。 */
        Entry orNone(long now, long retryMillis) {
            boolean gone = state == State.COLD || (state == State.FAILED && now - failedAt >= retryMillis);
            return pins == 0 && gone ? null : this;
        }
    }

    private final Map<SessionId, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration retry;

    /** 载入失败后 1 分钟再试。 */
    public Residency() {
        this(Clock.systemUTC(), Duration.ofMinutes(1));
    }

    /** {@code retry} 是配置项 {@code tantalum.residency.load-retry}。 */
    public Residency(Clock clock, Duration retry) {
        this.clock = clock;
        this.retry = retry;
    }

    /** 钉住一次，返回钉住前的状态和失败原因。失败超过重试时间的会话算 {@link State#COLD}，通道会重新载入。 */
    public Pinned pin(SessionId session) {
        AtomicReference<Pinned> seen = new AtomicReference<>();
        long now = clock.millis();
        entries.compute(session, (key, entry) -> {
            Entry current = entry == null ? new Entry(0, State.COLD, null, 0) : entry;
            // 过了重试时间：报 COLD 让通道重新载入，条目仍是 FAILED，再失败就不算第一次
            seen.set(expired(current, now) ? new Pinned(State.COLD, null) : new Pinned(current.state(), current.failure()));
            return current.pinned(1);
        });
        return seen.get();
    }

    public void unpin(SessionId session) {
        long now = clock.millis();
        long retryMillis = retry.toMillis();
        entries.computeIfPresent(session, (key, entry) ->
                (entry.pins() == 0 ? entry : entry.pinned(-1)).orNone(now, retryMillis));
    }

    public void resident(SessionId session) {
        entries.compute(session, (key, entry) -> new Entry(entry == null ? 0 : entry.pins(), State.RESIDENT, null, 0));
    }

    /** 载入了却没能交给 cleanroom：回到冷状态，下一个事件重新载入。钉住数不变。 */
    public void cold(SessionId session) {
        long now = clock.millis();
        long retryMillis = retry.toMillis();
        entries.computeIfPresent(session, (key, entry) -> new Entry(entry.pins(), State.COLD, null, 0).orNone(now, retryMillis));
    }

    /** 载入失败：重试时间内这个会话都拒绝。返回是不是第一次失败（之前不是失败状态），日志按它分级别。 */
    public boolean fail(SessionId session, String reason) {
        AtomicBoolean first = new AtomicBoolean();
        long now = clock.millis();
        entries.compute(session, (key, entry) -> {
            first.set(entry == null || entry.state() != State.FAILED);
            return new Entry(entry == null ? 0 : entry.pins(), State.FAILED, reason, now);
        });
        return first.get();
    }

    /** 载入失败的原因，没有失败、或者失败已过重试时间时为 null。 */
    public String failure(SessionId session) {
        Entry entry = entries.get(session);
        return entry == null || expired(entry, clock.millis()) ? null : entry.failure();
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

    private boolean expired(Entry entry, long now) {
        return entry.state() == State.FAILED && now - entry.failedAt() >= retry.toMillis();
    }
}
