package tantalum.shell.model;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 首块超时和块间超时。到时关掉正在读的流，让阻塞的读取结束。流由看门狗自己持有（{@link #watch}）：流对象发布时已经过期就立刻关掉，
 * 不会出现“看门狗关了个 null 然后退出、流再也没人管”（review-fixes 需求 10.1）。
 */
final class StreamWatchdog implements AutoCloseable {

    private final Duration betweenChunks;
    private final Thread thread;
    private final AtomicReference<AutoCloseable> stream = new AtomicReference<>();
    private volatile long deadline;
    private volatile boolean expired;
    private volatile boolean closed;

    StreamWatchdog(Duration firstChunk, Duration betweenChunks) {
        this.betweenChunks = betweenChunks;
        this.deadline = System.nanoTime() + firstChunk.toNanos();
        this.thread = Thread.ofVirtual().start(this::watch);
    }

    /** 拿到流就交给看门狗；已经过期的话这里就关。 */
    void watch(AutoCloseable opened) {
        stream.set(opened);
        if (expired) {
            abandon();
        }
    }

    /** 关掉持有的流（没有就什么都不做）。调用方在失败路径上用，免得流留着。 */
    void abandon() {
        AutoCloseable opened = stream.getAndSet(null);
        if (opened != null) {
            try {
                opened.close();
            } catch (Exception ignored) {
                // 已经在结束这次调用。
            }
        }
    }

    /** 收到任何内容时调用，包括保活注释。 */
    void activity() {
        deadline = System.nanoTime() + betweenChunks.toNanos();
    }

    boolean expired() {
        return expired;
    }

    private void watch() {
        try {
            while (!closed) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    // 到期和正常结束几乎同时发生时再看一眼，少一次把完整回复误报成超时。
                    if (closed) {
                        return;
                    }
                    expired = true;
                    abandon();
                    return;
                }
                Thread.sleep(Duration.ofNanos(Math.min(remaining, Duration.ofMillis(50).toNanos())));
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        closed = true;
        thread.interrupt();
    }
}
