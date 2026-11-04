package tantalum.shell.model;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 当前模型的上下文窗口（compaction-threshold T1、T2）。启动时读一次，最多等 {@code startupWait}；之后每
 * {@code refreshEvery} 在后台再读，模型报上下文超长时 {@link #refreshSoon()} 再读一次。读不到、读到的值不大于预留时
 * 保留当前值；从没读到时是配置值。同一时刻最多一个在读，读的时候再来的请求合并成读完之后的一次。日志只写异常类型，不写响应体和请求头。
 */
public final class ContextWindow implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ContextWindow.class);

    /** 窗口大小从哪来：模型报告的，或者配置的。 */
    public enum Source {
        MODEL, CONFIG
    }

    /** {@code tokens} 为 0 表示不知道（配置也是 0）。 */
    public record Reading(long tokens, Source source) {}

    private final WindowSource source;
    private final long reserve;
    private final Duration refreshEvery;
    private final AtomicBoolean queued = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("context-window").factory());
    private volatile Reading current;

    /** {@code reserve} 是给回复留的预算：读到的窗口不大于它时没法用，当作读失败。 */
    public ContextWindow(WindowSource source, long configured, long reserve, Duration refreshEvery) {
        this.source = source;
        this.reserve = reserve;
        this.refreshEvery = refreshEvery;
        this.current = new Reading(Math.max(0, configured), Source.CONFIG);
    }

    /** 读一次，最多等 {@code startupWait}；没读完就先用当前值，读完再换。然后开始定时刷新。 */
    public ContextWindow start(Duration startupWait) {
        if (source != WindowSource.NONE) {
            Future<?> first = executor.submit(this::readOnce);
            try {
                first.get(startupWait.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException slow) {
                LOG.warn("context window: no answer within {}; using {} ({}) until it arrives", startupWait,
                        current.tokens(), current.source());
            } catch (ExecutionException unexpected) {
                LOG.warn("context window: {}", unexpected.getCause().getClass().getSimpleName());
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
            }
            long every = refreshEvery.toMillis();
            executor.scheduleWithFixedDelay(this::refresh, every, every, TimeUnit.MILLISECONDS);
        }
        return this;
    }

    public Reading current() {
        return current;
    }

    /** 排一次立即读；已经排着的不再排。 */
    public void refreshSoon() {
        if (source != WindowSource.NONE && !executor.isShutdown() && queued.compareAndSet(false, true)) {
            executor.execute(this::refresh);
        }
    }

    private void refresh() {
        queued.set(false);
        readOnce();
    }

    private void readOnce() {
        try {
            OptionalLong read = source.read();
            if (read.isEmpty()) {
                LOG.warn("context window: the model reports none; keeping {} ({})", current.tokens(), current.source());
            } else if (read.getAsLong() <= reserve) {
                LOG.warn("context window: {} is not above the reserve {}; keeping {} ({})", read.getAsLong(), reserve,
                        current.tokens(), current.source());
            } else {
                Reading next = new Reading(read.getAsLong(), Source.MODEL);
                if (!next.equals(current)) {
                    LOG.info("context window {} ({}) -> {} ({})", current.tokens(), current.source(), next.tokens(), next.source());
                    current = next;
                }
            }
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            // 别的异常的说明可能带响应体，只写类名
            String reason = failure instanceof WindowSource.Unreadable ? failure.getMessage() : failure.getClass().getSimpleName();
            LOG.warn("context window: {}; keeping {} ({})", reason, current.tokens(), current.source());
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
