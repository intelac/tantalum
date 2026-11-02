package tantalum.shell.loop;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.shell.conversation.Transcript;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.SessionId;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/**
 * 决定哪些会话离开内存，什么时候写快照。只在 cleanroom 线程上调用，不做 I/O：快照交给 {@link Sink}。
 * 条件是钉住为零、没有活动运行、没有排队提交，且空闲超时或超过常驻上限。
 */
public final class Evictor {

    /** {@code idle} 为 null 时不按时间回收。{@code snapshotEvents} 是运行结束时写快照的事件数门槛。 */
    public record Settings(Duration idle, int maxResident, int snapshotEvents, Duration sweepInterval) {

        public static final Settings NEVER = new Settings(null, Integer.MAX_VALUE, Integer.MAX_VALUE, Duration.ofSeconds(1));
    }

    /** 接收要写的快照，不能阻塞。参数都不可变。 */
    @FunctionalInterface
    public interface Sink {
        Sink NONE = (session, runs, transcript, seq) -> { };

        void offer(SessionState session, List<RunState> runs, Transcript.Part transcript, long seq);
    }

    private static final class Entry {
        long lastUsed;
        long seq;
        long snapshotSeq = -1;
        int sinceSnapshot;
    }

    private final Settings settings;
    private final Residency residency;
    private final Projection projection;
    private final Sink sink;
    private final Clock clock;
    /** 按访问顺序，最久没用的在前。 */
    private final Map<SessionId, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long lastSweep = Long.MIN_VALUE / 2;

    public Evictor(Settings settings, Residency residency, Projection projection, Sink sink, Clock clock) {
        this.settings = settings;
        this.residency = residency;
        this.projection = projection;
        this.sink = sink;
        this.clock = clock;
    }

    public static Evictor never(Projection projection) {
        return new Evictor(Settings.NEVER, new Residency(), projection, Sink.NONE, Clock.systemUTC());
    }

    /** 启动时已经在内存的会话。 */
    public void recovered(SessionId session, long seq) {
        Entry entry = entry(session);
        entry.seq = seq;
    }

    /** 并入一个载入的会话。全部来自快照时，磁盘上的快照已经是最新的。 */
    public void loaded(SessionId session, Preload preload) {
        Entry entry = entry(session);
        entry.seq = preload.seq();
        entry.sinceSnapshot = preload.replayed();
        entry.snapshotSeq = preload.replayed() == 0 ? preload.seq() : -1;
    }

    /** 处理完一个事件后调用。运行结束且距上次快照够远时写一份。 */
    public void stepped(EventEnvelope envelope, KernelState before, KernelState after) {
        SessionId session = envelope.session();
        Entry entry = entry(session);
        entry.seq = envelope.seq();
        entry.sinceSnapshot++;
        SessionState previous = before.sessions().get(session);
        SessionState current = after.sessions().get(session);
        boolean runEnded = previous != null && previous.activeRun() != null && current != null && current.activeRun() == null;
        if (runEnded && entry.sinceSnapshot >= settings.snapshotEvents()) {
            snapshot(after, current, entry);
        }
    }

    /** 这个事件不再在途。 */
    public void finished(SessionId session) {
        residency.unpin(session);
    }

    /** 回收满足条件的会话，返回去掉它们之后的状态。最久没用的在前，遇到第一个不用回收的就停。 */
    public KernelState sweep(KernelState state) {
        long now = clock.millis();
        if (now - lastSweep < settings.sweepInterval().toMillis()) {
            return state;
        }
        lastSweep = now;
        KernelState result = state;
        Iterator<Map.Entry<SessionId, Entry>> oldest = entries.entrySet().iterator();
        while (oldest.hasNext()) {
            Map.Entry<SessionId, Entry> next = oldest.next();
            if (!overLimit() && !idleTooLong(next.getValue(), now)) {
                break;
            }
            SessionState session = result.sessions().get(next.getKey());
            if (evictable(session) && residency.evict(next.getKey())) {
                if (session != null && next.getValue().snapshotSeq != next.getValue().seq) {
                    snapshot(result, session, next.getValue());
                }
                result = result.without(next.getKey());
                projection.remove(next.getKey());
                oldest.remove();
            }
        }
        return result;
    }

    /** 关闭时调用：给没有活动运行、快照又不是最新的会话各写一份。cleanroom 已经停了才能调。 */
    public void snapshotAll(KernelState state) {
        for (Map.Entry<SessionId, Entry> next : entries.entrySet()) {
            SessionState session = state.sessions().get(next.getKey());
            if (session != null && session.activeRun() == null && next.getValue().snapshotSeq != next.getValue().seq) {
                snapshot(state, session, next.getValue());
            }
        }
    }

    private boolean overLimit() {
        return entries.size() > settings.maxResident();
    }

    private boolean idleTooLong(Entry entry, long now) {
        return settings.idle() != null && now - entry.lastUsed >= settings.idle().toMillis();
    }

    /** 内核不认识的会话也有条目（比如对不存在的会话取消），没有状态要保留。 */
    private static boolean evictable(SessionState session) {
        return session == null || (session.activeRun() == null && session.followUps().isEmpty());
    }

    private void snapshot(KernelState state, SessionState session, Entry entry) {
        List<RunState> runs = state.runs().values().stream().filter(run -> run.ref().session().equals(session.id())).toList();
        sink.offer(session, runs, projection.export(session.id()), entry.seq);
        entry.snapshotSeq = entry.seq;
        entry.sinceSnapshot = 0;
    }

    private Entry entry(SessionId session) {
        Entry entry = entries.computeIfAbsent(session, ignored -> new Entry());
        entry.lastUsed = clock.millis();
        return entry;
    }

    public int resident() {
        return entries.size();
    }
}
