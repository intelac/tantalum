package tantalum.shell.loop;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.value.event.EventEnvelope;

/** 输入环。外部提交和 Handler 结果都进这里，唯一消费者是 {@link LaneDispatcher}。只用非阻塞发布。 */
public final class InputRing {

    private static final Logger LOG = LoggerFactory.getLogger(InputRing.class);

    /** 环上的一格。 */
    public static final class Slot {
        public EventEnvelope envelope;
        /** 只有 Cleanroom Ring 用：这个事件的会话刚载入，处理前要并入。 */
        public Preload preload;
    }

    private static final long DRAIN_POLL_MILLIS = 5;

    private final Disruptor<Slot> disruptor;
    private final RingFailure<Slot> failure = new RingFailure<>("input");

    public InputRing(int size) {
        disruptor = new Disruptor<>(Slot::new, size, DaemonThreadFactory.INSTANCE,
                ProducerType.MULTI, new BlockingWaitStrategy());
        disruptor.setDefaultExceptionHandler(failure);
    }

    /** 消费者要用到发布方，发布方又要用到环，所以分两步装配。 */
    public void connect(EventHandler<Slot> consumer) {
        disruptor.handleEventsWith(consumer);
    }

    public void start() {
        disruptor.start();
    }

    public boolean tryPublish(EventEnvelope envelope) {
        return disruptor.getRingBuffer().tryPublishEvent((slot, sequence) -> slot.envelope = envelope);
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

    /** 某个消费者抛出过异常。事件本身已跳过，环还在转，但状态不再可信。 */
    public boolean broken() {
        return failure.broken();
    }

    /** 先把已发布的事件处理完（客户端已经收到 200 的提交不能丢），超时再强制停止。 */
    public void shutdown(Duration drain) {
        try {
            disruptor.shutdown(drain.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException stillBusy) {
            LOG.warn("input ring did not drain within {}; halting", drain);
            disruptor.halt();
        }
    }
}
