package tantalum.shell.journal;

import java.util.Map;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.CommandId;

/** 会话文件头部之后的一行：一个事件，或内核处理某个事件的结果（record-results）。 */
public sealed interface Line {

    long seq();

    record Event(EventEnvelope envelope) implements Line {
        @Override
        public long seq() {
            return envelope.seq();
        }
    }

    /** {@code fingerprints} 只在读出时有值：这一步每次模型调用当时记下的请求指纹，请求详情页用来核对（需求 4.2）。 */
    record Result(StepRecord record, Map<CommandId, String> fingerprints) implements Line {

        public Result {
            fingerprints = Map.copyOf(fingerprints);
        }

        public Result(StepRecord record) {
            this(record, Map.of());
        }

        @Override
        public long seq() {
            return record.seq();
        }
    }
}
