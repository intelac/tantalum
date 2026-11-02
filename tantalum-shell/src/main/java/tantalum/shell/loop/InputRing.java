package tantalum.shell.loop;

import com.lmax.disruptor.dsl.ProducerType;
import tantalum.value.event.EventEnvelope;

/** 输入环。外部提交和 Handler 结果都进这里，唯一消费者是 {@link LaneDispatcher}。 */
public final class InputRing extends Ring<InputRing.Slot> {

    /** 环上的一格。 */
    public static final class Slot {
        public EventEnvelope envelope;
    }

    public InputRing(int size) {
        super("input", size, Slot::new, ProducerType.MULTI);
    }

    public boolean tryPublish(EventEnvelope envelope) {
        return tryPublish((slot, sequence) -> slot.envelope = envelope);
    }
}
