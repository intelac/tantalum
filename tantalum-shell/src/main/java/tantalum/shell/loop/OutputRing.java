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
import tantalum.value.command.Command;

/** 输出环，只放命令。只有 {@code Cleanroom} 一个生产者，只用非阻塞发布。 */
public final class OutputRing {

    private static final Logger LOG = LoggerFactory.getLogger(OutputRing.class);

    /** 环上的一格。 */
    public static final class Slot {
        public Command command;
    }

    private final Disruptor<Slot> disruptor;
    private final RingFailure<Slot> failure = new RingFailure<>("output");

    public OutputRing(int size, EventHandler<Slot> consumer) {
        disruptor = new Disruptor<>(Slot::new, size, DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE, new BlockingWaitStrategy());
        disruptor.setDefaultExceptionHandler(failure);
        disruptor.handleEventsWith(consumer);
    }

    public void start() {
        disruptor.start();
    }

    public boolean tryPublish(Command command) {
        return disruptor.getRingBuffer().tryPublishEvent((slot, sequence) -> slot.command = command);
    }

    /** 分发器抛出过异常。那条命令没有发出去，对应运行会等到超时。 */
    public boolean broken() {
        return failure.broken();
    }

    public void shutdown() {
        disruptor.halt();
    }

    /** 先把已发布的命令分发完，超时再强制停止。 */
    public void shutdown(Duration drain) {
        try {
            disruptor.shutdown(drain.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException stillBusy) {
            LOG.warn("output ring did not drain within {}; halting", drain);
            disruptor.halt();
        }
    }
}
