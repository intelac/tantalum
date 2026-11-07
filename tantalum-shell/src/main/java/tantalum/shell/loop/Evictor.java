package tantalum.shell.loop;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import tantalum.value.id.SessionId;
import tantalum.value.state.AwaitingAnswer;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/**
 * 决定哪些会话离开内存。只在 cleanroom 线程上调用，不做 I/O：离开的会话下次从会话文件重放。
 * 条件是钉住为零、没有活动运行（或运行停在等用户回答）、没有排队提交，且空闲超时或超过常驻上限。
 */
public final class Evictor {

    /** 配置项 {@code tantalum.residency}。{@code idle} 为 null 时不按时间回收。 */
    public record Settings(Duration idle, int maxResident, Duration sweepInterval) {

        public Settings {
            if (maxResident <= 0) {
                throw new IllegalArgumentException("maxResident must be positive but was " + maxResident);
            }
        }

        /** 空闲 30 分钟或超过 1000 个常驻时回收。 */
        public static Settings defaults() {
            return new Settings(Duration.ofMinutes(30), 1000, Duration.ofSeconds(1));
        }
    }

    private static final class Entry {
        long lastUsed;
    }

    private final Settings settings;
    private final Residency residency;
    private final Projections projections;
    private final Clock clock;
    private final Consumer<SessionId> evicted;
    /** 按访问顺序，最久没用的在前。 */
    private final Map<SessionId, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long lastSweep = Long.MIN_VALUE / 2;

    public Evictor(Settings settings, Residency residency, Projections projections, Clock clock) {
        this(settings, residency, projections, clock, session -> {});
    }

    /** {@code evicted} 在会话离开内存后调用，让 shell 里按会话记的东西（如先读后写的记录）一起丢掉。 */
    public Evictor(Settings settings, Residency residency, Projections projections, Clock clock, Consumer<SessionId> evicted) {
        this.settings = settings;
        this.residency = residency;
        this.projections = projections;
        this.clock = clock;
        this.evicted = evicted;
    }

    /** 会话刚载入或处理完一个事件后调用，记下最近使用的时间。 */
    public void used(SessionId session) {
        Entry entry = entries.computeIfAbsent(session, ignored -> new Entry());
        entry.lastUsed = clock.millis();
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
            if (evictable(session, result) && residency.evict(next.getKey())) {
                result = result.without(next.getKey());
                projections.remove(next.getKey());
                evicted.accept(next.getKey());
                oldest.remove();
            }
        }
        return result;
    }

    private boolean overLimit() {
        return entries.size() > settings.maxResident();
    }

    private boolean idleTooLong(Entry entry, long now) {
        return settings.idle() != null && now - entry.lastUsed >= settings.idle().toMillis();
    }

    /**
     * 内核不认识的会话也有条目（比如对不存在的会话取消），没有状态要保留。有活动运行的只有一种可以走：停在等用户回答的
     * （waiting-state D3）——它没有在途命令，状态全在 journal 里，回答来了再载入；等命令结果的不能走。
     */
    private static boolean evictable(SessionState session, KernelState state) {
        if (session == null) {
            return true;
        }
        if (!session.followUps().isEmpty()) {
            return false;
        }
        if (session.activeRun() == null) {
            return true;
        }
        RunState run = state.runs().get(session.activeRun());
        return run != null && run.phase() instanceof AwaitingAnswer;
    }

    public int resident() {
        return entries.size();
    }
}
