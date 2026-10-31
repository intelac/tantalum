package tantalum.shell.loop;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import tantalum.value.command.Command;

/** 输出环，只放命令。只有 {@code Cleanroom} 一个生产者，只用非阻塞发布。 */
public final class OutputRing {

    /** 环上的一格。 */
    public static final class Slot {
        public Command command;
    }

    private final Disruptor<Slot> disruptor;

    public OutputRing(int size, EventHandler<Slot> consumer) {
        disruptor = new Disruptor<>(Slot::new, size, DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE, new BlockingWaitStrategy());
        disruptor.handleEventsWith(consumer);
    }

    public void start() {
        disruptor.start();
    }

    public boolean tryPublish(Command command) {
        return disruptor.getRingBuffer().tryPublishEvent((slot, sequence) -> slot.command = command);
    }

    public void shutdown() {
        disruptor.halt();
    }
}
