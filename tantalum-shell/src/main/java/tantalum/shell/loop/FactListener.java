package tantalum.shell.loop;

import java.util.List;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.shell.trace.TraceRecord;
import tantalum.value.decision.Fact;
import tantalum.value.id.Principal;

/**
 * 接收内核产生的事实、对话条目和轨迹记录的变化。必须立即返回，不能阻塞。
 * {@code seq} 和 {@code index} 定位一条通知：同一事件里先数事实，再数条目，最后数轨迹记录，回放时相同。
 */
@FunctionalInterface
public interface FactListener {

    void onFact(long seq, int index, Fact fact);

    default void onItem(long seq, int index, Principal owner, TranscriptItem item) {
    }

    default void onRecord(long seq, int index, Principal owner, TraceRecord record) {
    }

    /** 一个事件的轨迹记录逐条通知完之后，再整批给一次（运行表按事件汇总用）；没有记录的事件不调。 */
    default void onRecords(long seq, Principal owner, List<TraceRecord> records) {
    }

    /** 先 {@code first} 后 {@code second}；一个抛出不影响另一个收到。 */
    static FactListener both(FactListener first, FactListener second) {
        return new FactListener() {
            @Override
            public void onFact(long seq, int index, Fact fact) {
                try {
                    first.onFact(seq, index, fact);
                } finally {
                    second.onFact(seq, index, fact);
                }
            }

            @Override
            public void onItem(long seq, int index, Principal owner, TranscriptItem item) {
                try {
                    first.onItem(seq, index, owner, item);
                } finally {
                    second.onItem(seq, index, owner, item);
                }
            }

            @Override
            public void onRecord(long seq, int index, Principal owner, TraceRecord record) {
                try {
                    first.onRecord(seq, index, owner, record);
                } finally {
                    second.onRecord(seq, index, owner, record);
                }
            }

            @Override
            public void onRecords(long seq, Principal owner, List<TraceRecord> records) {
                try {
                    first.onRecords(seq, owner, records);
                } finally {
                    second.onRecords(seq, owner, records);
                }
            }
        };
    }
}
