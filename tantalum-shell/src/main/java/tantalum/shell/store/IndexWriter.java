package tantalum.shell.store;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.KernelState;

/**
 * 索引库的写入线程。cleanroom 只把变化放进内存队列，这里批量写：运行结束时必写，其余攒够事件数或到时间。
 * 写失败只记日志并留到下次重试，服务继续。积压过多时放弃本次进程的全部写入，
 * 文件位置因此停在旧值，下次启动会从文件补齐，不会有缺口被位置盖住。
 */
public final class IndexWriter implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IndexWriter.class);
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(10);
    private static final int MAX_PENDING = 100_000;

    /** 写到哪里，测试用来模拟失败。 */
    @FunctionalInterface
    interface Target {
        void write(IndexBatch batch) throws SQLException;
    }

    private record Durable(long seq, long offset) {}

    private record Barrier(CountDownLatch done) {}

    private static final Object STOP = new Object();

    private final Target target;
    private final int flushEvents;
    private final long flushNanos;
    private final int maxPending;
    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final Map<SessionId, Queue<Durable>> durable = new ConcurrentHashMap<>();
    private final Thread thread;
    private IndexBatch pending = new IndexBatch();
    private long deadline;
    private long retryAt;
    private volatile boolean abandoned;

    public IndexWriter(SessionIndex index, int flushEvents, Duration flushInterval) {
        this(index::write, flushEvents, flushInterval, MAX_PENDING);
    }

    IndexWriter(Target target, int flushEvents, Duration flushInterval, int maxPending) {
        this.target = target;
        this.flushEvents = flushEvents;
        this.flushNanos = flushInterval.toNanos();
        this.maxPending = maxPending;
        this.thread = Thread.ofPlatform().daemon().name("index-writer").start(this::run);
    }

    /** 一步落盘后由写入通道调用，各用户的通道并发调用。不做 I/O，不等待。 */
    public void onStep(EventEnvelope envelope, KernelState before, KernelState after) {
        if (!abandoned) {
            queue.add(IndexChange.of(envelope, before, after));
        }
    }

    /** 文件写入线程调用：序号不超过 {@code seq} 的事件已落盘，文件到 {@code offset}。总在对应事件进入 cleanroom 之前。 */
    public void onDurable(Principal principal, SessionId session, long seq, long offset) {
        if (!abandoned) {
            durable.computeIfAbsent(session, ignored -> new ConcurrentLinkedQueue<>()).add(new Durable(seq, offset));
        }
    }

    /** 等已入队的变化写完。写失败也会返回，此时索引落后。 */
    public void flush() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        queue.add(new Barrier(done));
        while (!done.await(100, TimeUnit.MILLISECONDS) && thread.isAlive()) {
            // 线程意外退出时不再等。
        }
    }

    private void run() {
        try {
            while (true) {
                Object item = pending.isEmpty() ? queue.take() : queue.poll(waitNanos(), TimeUnit.NANOSECONDS);
                switch (item) {
                    case null -> write(false);
                    case IndexChange change -> accept(change);
                    case Barrier barrier -> {
                        write(true);
                        barrier.done().countDown();
                    }
                    default -> {
                        write(true);
                        return;
                    }
                }
            }
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error failure) {
            abandoned = true;
            LOG.error("index writer stopped; the index will catch up on next start", failure);
        }
    }

    private long waitNanos() {
        return Math.max(0, Math.max(deadline, retryAt) - System.nanoTime());
    }

    private void accept(IndexChange change) {
        if (abandoned) {
            return;
        }
        if (pending.isEmpty()) {
            deadline = System.nanoTime() + flushNanos;
        }
        pending.add(change);
        if (pending.events() > maxPending) {
            abandoned = true;
            pending = new IndexBatch();
            durable.clear();
            LOG.error("index writes keep failing, giving up until restart; the index catches up from session files on next start");
            return;
        }
        if (change.runEnded() || pending.events() >= flushEvents) {
            write(false);
        }
    }

    /** {@code force} 为 false 时，上次失败后的重试间隔内不再尝试。 */
    private void write(boolean force) {
        if (pending.isEmpty() || (!force && System.nanoTime() < retryAt)) {
            return;
        }
        resolveOffsets();
        try {
            target.write(pending);
            pending = new IndexBatch();
            retryAt = 0;
        } catch (SQLException | RuntimeException failure) {
            retryAt = System.nanoTime() + flushNanos;
            LOG.warn("index write failed, {} events wait for retry: {}", pending.events(), failure.toString());
        }
    }

    /** 一批里只有最后一个事件知道文件位置。停在中间时用更早的位置：下次启动多重放一点，不会漏。 */
    private void resolveOffsets() {
        for (IndexBatch.Row row : pending.sessions.values()) {
            Queue<Durable> marks = durable.get(row.session);
            if (marks == null) {
                continue;
            }
            Durable head;
            while ((head = marks.peek()) != null && head.seq() <= row.lastSeq) {
                row.fileOffset = Math.max(row.fileOffset, head.offset());
                marks.poll();
            }
            if (marks.isEmpty()) {
                durable.remove(row.session, marks);
            }
        }
    }

    /** 写完剩下的再退出。之后不能再调用 {@link #onStep}。 */
    @Override
    public void close() throws InterruptedException {
        queue.add(STOP);
        thread.join(CLOSE_WAIT.toMillis());
    }
}
