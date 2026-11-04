package tantalum.shell.loop;

import com.lmax.disruptor.dsl.ProducerType;
import tantalum.value.event.EventEnvelope;
import tantalum.value.state.PrincipalState;

/** 已写入会话文件的事件进入内核的环（落盘随结果行一起，见 {@link UserLane}）。各用户通道是生产者，唯一消费者是 cleanroom。放行名额保证它不会满。 */
public final class CleanroomRing extends Ring<CleanroomRing.Slot> {

    /** 环上的一格。cleanroom 处理事件前先并入 {@code usage} 和 {@code load}。 */
    public static final class Slot {
        public EventEnvelope envelope;
        /** 这个用户在本进程第一个事件之前的累计用量，只随他的第一个事件来一次；其余为 null。 */
        public PrincipalState usage;
        /** 这个事件的会话刚载入或载入失败；会话已在内存时为 null。 */
        public Load load;
    }

    /** 通道载入冷会话的结果。 */
    public sealed interface Load permits Loaded, Failed {}

    /** 载入成功，只随这个会话的第一个事件来。 */
    public record Loaded(SessionSlice slice) implements Load {}

    /** 载入失败，这个会话的每个事件都带着它。 */
    public record Failed(String reason) implements Load {}

    public CleanroomRing(int size) {
        super("cleanroom", size, Slot::new, ProducerType.MULTI);
    }

    public boolean tryPublish(EventEnvelope envelope, PrincipalState usage, Load load) {
        return tryPublish((slot, sequence) -> {
            slot.envelope = envelope;
            slot.usage = usage;
            slot.load = load;
        });
    }
}
