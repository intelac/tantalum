package tantalum.shell.loop;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslator;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 三个环共用的 Disruptor 装配。各环只定义自己的格子和发布方法；只用非阻塞发布。 */
public class Ring<S> {

    private static final Logger LOG = LoggerFactory.getLogger(Ring.class);
    private static final long DRAIN_POLL_MILLIS = 5;

    private final String name;
    private final Disruptor<S> disruptor;
    private final RingFailure<S> failure;

    protected Ring(String name, int size, EventFactory<S> slots, ProducerType producers) {
        this.name = name;
        this.failure = new RingFailure<>(name);
        this.disruptor = new Disruptor<>(slots, size, DaemonThreadFactory.INSTANCE, producers, new BlockingWaitStrategy());
        disruptor.setDefaultExceptionHandler(failure);
    }

    /** 消费者要用到发布方，发布方又要用到环，所以分两步装配。 */
    public void connect(EventHandler<S> consumer) {
        disruptor.handleEventsWith(consumer);
    }

    public void start() {
        disruptor.start();
    }

    /** 环满时立即返回 false。 */
    protected boolean tryPublish(EventTranslator<S> fill) {
        return disruptor.getRingBuffer().tryPublishEvent(fill);
    }

    public long remainingCapacity() {
        return disruptor.getRingBuffer().remainingCapacity();
    }

    /** 等已发布的事件都被消费者取走。超时返回 false。 */
    public boolean awaitDrained(Duration wait) throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (disruptor.getRingBuffer().getMinimumGatingSequence() < disruptor.getCursor()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(DRAIN_POLL_MILLIS);
        }
        return true;
    }

    /** 消费者抛出过异常。那一格已跳过，环还在转，但下游状态不再可信。 */
    public boolean broken() {
        return failure.broken();
    }

    /** 先把已发布的处理完（客户端已经收到 200 的提交不能丢），超时再强制停止。 */
    public void shutdown(Duration drain) {
        try {
            disruptor.shutdown(drain.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException stillBusy) {
            LOG.warn("{} ring did not drain within {}; halting", name, drain);
            disruptor.halt();
        }
    }
}
