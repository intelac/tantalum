package tantalum.shell.journal;

import java.util.Set;
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

    /** {@code fingerprintMismatches} 只在读出时有值：还原的请求和当时记下的指纹不一致的模型调用。 */
    record Result(StepRecord record, Set<CommandId> fingerprintMismatches) implements Line {

        public Result {
            fingerprintMismatches = Set.copyOf(fingerprintMismatches);
        }

        public Result(StepRecord record) {
            this(record, Set.of());
        }

        @Override
        public long seq() {
            return record.seq();
        }
    }
}
