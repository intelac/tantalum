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

/** 已落盘事件进入内核的环。各用户通道是生产者，唯一消费者是 cleanroom。放行名额保证它不会满。 */
public final class CleanroomRing {

    private static final Logger LOG = LoggerFactory.getLogger(CleanroomRing.class);

    private final Disruptor<InputRing.Slot> disruptor;
    private final RingFailure<InputRing.Slot> failure = new RingFailure<>("cleanroom");

    public CleanroomRing(int size) {
        disruptor = new Disruptor<>(InputRing.Slot::new, size, DaemonThreadFactory.INSTANCE,
                ProducerType.MULTI, new BlockingWaitStrategy());
        disruptor.setDefaultExceptionHandler(failure);
    }

    public void connect(EventHandler<InputRing.Slot> consumer) {
        disruptor.handleEventsWith(consumer);
    }

    public void start() {
        disruptor.start();
    }

    public boolean tryPublish(EventEnvelope envelope, Preload preload) {
        return disruptor.getRingBuffer().tryPublishEvent((slot, sequence) -> {
            slot.envelope = envelope;
            slot.preload = preload;
        });
    }

    public boolean broken() {
        return failure.broken();
    }

    /** 先把已发布的事件处理完，超时再强制停止。 */
    public void shutdown(Duration drain) {
        try {
            disruptor.shutdown(drain.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException stillBusy) {
            LOG.warn("cleanroom ring did not drain within {}; halting", drain);
            disruptor.halt();
        }
    }
}
