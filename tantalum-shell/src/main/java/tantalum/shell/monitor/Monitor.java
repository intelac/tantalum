package tantalum.shell.monitor;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/**
 * 监视流（live-monitor 需求 1、2）：按用户的订阅者，每个一个有界队列，满了丢最旧的并记数。只在内存里。
 * 没有订阅者时 {@link #watching} 是一次查表；{@link #at} 不阻塞、不抛出（出错限频记日志）。
 */
public final class Monitor implements Probe {

    public static final int QUEUE = 2048;
    private static final Logger LOG = LoggerFactory.getLogger(Monitor.class);
    private static final long LOG_EVERY_NANOS = TimeUnit.MINUTES.toNanos(1);
    /** 关闭时放进队列，叫醒正在等的 {@link Subscription#poll}：否则推送线程要等到心跳超时，关机被拖住。 */
    private static final MonitorMessage CLOSED = new MonitorMessage(Stage.INTAKE, null, null, null, null, null, null, 0, 0);

    /** 一个订阅：{@link #poll} 取下一条，用完 {@link #close}。 */
    public final class Subscription implements AutoCloseable {
        private final Principal user;
        private final ArrayBlockingQueue<MonitorMessage> queue = new ArrayBlockingQueue<>(QUEUE);
        private final AtomicInteger dropped = new AtomicInteger();
        private volatile boolean closed;

        private Subscription(Principal user) {
            this.user = user;
        }

        /** 等最多 {@code timeout}；没有时为 null。前面丢过的条数带在这一条上。 */
        public MonitorMessage poll(long timeout, TimeUnit unit) throws InterruptedException {
            if (closed) {
                return null;
            }
            MonitorMessage next = queue.poll(timeout, unit);
            if (next == null || next == CLOSED) {
                return null;
            }
            int lost = dropped.getAndSet(0);
            return lost == 0 ? next : next.withDropped(lost);
        }

        public boolean closed() {
            return closed;
        }

        void offer(MonitorMessage message) {
            while (!queue.offer(message)) {
                if (queue.poll() != null) {
                    dropped.incrementAndGet();
                }
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            subscribers.computeIfPresent(user, (key, list) -> {
                list.remove(this);
                return list.isEmpty() ? null : list;
            });
            queue.clear();
            queue.offer(CLOSED);
        }
    }

    private final Map<Principal, List<Subscription>> subscribers = new ConcurrentHashMap<>();
    private final Clock clock;
    private final AtomicLong lastLog = new AtomicLong(System.nanoTime() - LOG_EVERY_NANOS);

    public Monitor(Clock clock) {
        this.clock = clock;
    }

    public Subscription subscribe(Principal user) {
        Subscription subscription = new Subscription(user);
        subscribers.computeIfAbsent(user, ignored -> new CopyOnWriteArrayList<>()).add(subscription);
        return subscription;
    }

    /** 关机：断开所有订阅，免得长连接拖住停止。 */
    public void closeAll() {
        subscribers.values().forEach(list -> List.copyOf(list).forEach(Subscription::close));
    }

    /** 所有用户的订阅者数（测试和日志用）。 */
    public int subscribers() {
        return subscribers.values().stream().mapToInt(List::size).sum();
    }

    @Override
    public boolean watching(Principal user) {
        return user != null && subscribers.containsKey(user);
    }

    @Override
    public void at(Principal user, Stage stage, SessionId session, RunId run, Long seq, String command, String name, String detail) {
        try {
            List<Subscription> list = subscribers.get(user);
            if (list == null) {
                return;
            }
            MonitorMessage message = new MonitorMessage(stage, session == null ? null : session.value(), run == null ? null : run.value(), seq,
                    command, name, detail, clock.millis(), 0);
            for (Subscription subscription : list) {
                subscription.offer(message);
            }
        } catch (RuntimeException failure) {
            long now = System.nanoTime();
            long last = lastLog.get();
            if (now - last >= LOG_EVERY_NANOS && lastLog.compareAndSet(last, now)) {
                LOG.warn("monitor probe failed at {}", stage, failure);
            }
        }
    }
}
