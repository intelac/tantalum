package tantalum.shell.loop;

import com.lmax.disruptor.dsl.ProducerType;
import tantalum.shell.monitor.Probe;
import tantalum.shell.monitor.Probes;
import tantalum.shell.monitor.Stage;
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

    private volatile Probe probe = Probe.NONE;

    public boolean tryPublish(EventEnvelope envelope) {
        boolean published = tryPublish((slot, sequence) -> slot.envelope = envelope);
        if (published) {
            Probes.event(probe, Stage.INPUT, envelope, null);
        }
        return published;
    }

    /** 实时监视的埋点（live-monitor）；入口也经这里报 {@link Stage#INTAKE}。 */
    public void watch(Probe probe) {
        this.probe = probe;
    }

    Probe probe() {
        return probe;
    }
}
