package tantalum.shell.loop;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import tantalum.value.event.EventEnvelope;

/** 输入环。第二个消费者依赖第一个的进度，这就是“先落盘再处理”。只用非阻塞发布。 */
public final class InputRing {

    /** 环上的一格。 */
    public static final class Slot {
        public EventEnvelope envelope;
    }

    private final Disruptor<Slot> disruptor;

    public InputRing(int size) {
        disruptor = new Disruptor<>(Slot::new, size, DaemonThreadFactory.INSTANCE,
                ProducerType.MULTI, new BlockingWaitStrategy());
    }

    public InputRing(int size, EventHandler<Slot> first, EventHandler<Slot> second) {
        this(size);
        connect(first, second);
    }

    /** 消费者要用到发布方，发布方又要用到环，所以分两步装配。 */
    public void connect(EventHandler<Slot> first, EventHandler<Slot> second) {
        disruptor.handleEventsWith(first).then(second);
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

    public void shutdown() {
        disruptor.halt();
    }
}
