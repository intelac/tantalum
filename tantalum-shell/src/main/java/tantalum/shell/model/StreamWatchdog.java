package tantalum.shell.model;

import java.time.Duration;

/** 首块超时和块间超时。到时调用回调，通常是关闭流，让阻塞的读取结束。 */
final class StreamWatchdog implements AutoCloseable {

    private final Duration betweenChunks;
    private final Runnable onExpire;
    private final Thread thread;
    private volatile long deadline;
    private volatile boolean expired;
    private volatile boolean closed;

    StreamWatchdog(Duration firstChunk, Duration betweenChunks, Runnable onExpire) {
        this.betweenChunks = betweenChunks;
        this.onExpire = onExpire;
        this.deadline = System.nanoTime() + firstChunk.toNanos();
        this.thread = Thread.ofVirtual().start(this::watch);
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
                    onExpire.run();
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
