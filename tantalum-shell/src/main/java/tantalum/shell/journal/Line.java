package tantalum.shell.journal;

import java.util.Map;
import java.util.Objects;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.CommandId;

/**
 * 会话文件头部之后的一行：一个事件，和内核处理它的结果。内核处理完才写，所以每一行都是完整的一步。
 * {@code fingerprints} 只在读出时有值：这一步每次模型调用当时记下的请求指纹，请求详情页用来核对。
 */
public record Line(EventEnvelope envelope, StepRecord record, Map<CommandId, String> fingerprints) {

    public Line {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(record, "record");
        if (record.seq() != envelope.seq()) {
            throw new IllegalArgumentException("result of seq " + record.seq() + " does not belong to event seq " + envelope.seq());
        }
        fingerprints = Map.copyOf(fingerprints);
    }

    public Line(EventEnvelope envelope, StepRecord record) {
        this(envelope, record, Map.of());
    }

    public long seq() {
        return envelope.seq();
    }
}
