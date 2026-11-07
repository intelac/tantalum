package tantalum.shell.loop;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.dsl.ProducerType;
import tantalum.value.command.Command;

/** 输出环，只放命令。生产者是各用户的写入通道：结果行落盘后才放行。分发器抛出时那条命令没有发出去，对应运行会等到超时。 */
public final class OutputRing extends Ring<OutputRing.Slot> {

    /** 环上的一格。 */
    public static final class Slot {
        public Command command;
    }

    public OutputRing(int size, EventHandler<Slot> consumer) {
        super("output", size, Slot::new, ProducerType.MULTI);
        connect(consumer);
    }

    public boolean tryPublish(Command command) {
        return tryPublish((slot, sequence) -> slot.command = command);
    }
}
