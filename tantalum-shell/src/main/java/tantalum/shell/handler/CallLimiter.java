package tantalum.shell.handler;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 限制同时进行的调用数。等待可超时、可中断，只占虚拟线程。 */
public final class CallLimiter {

    /** 一个名额，关闭时归还。 */
    public final class Permit implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                permits.release();
            }
        }
    }

    private final Semaphore permits;

    public CallLimiter(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive but was " + limit);
        }
        this.permits = new Semaphore(limit, true);
    }

    public Permit acquire(Duration timeout) throws InterruptedException, TimeoutException {
        if (!permits.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
            throw new TimeoutException("no capacity within " + timeout);
        }
        return new Permit();
    }

    public int available() {
        return permits.availablePermits();
    }
}
