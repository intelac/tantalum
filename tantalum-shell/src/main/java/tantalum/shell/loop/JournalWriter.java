package tantalum.shell.loop;

import com.lmax.disruptor.EventHandler;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.journal.Journal;
import tantalum.value.event.EventEnvelope;

/** 输入环的第一个消费者：分配序号、写入 journal、批尾提交。写入失败后进程停止处理事件。 */
public final class JournalWriter implements EventHandler<InputRing.Slot> {

    private static final Logger LOG = LoggerFactory.getLogger(JournalWriter.class);

    private final Journal journal;
    private long nextSeq;
    private volatile boolean halted;

    public JournalWriter(Journal journal, long nextSeq) {
        this.journal = journal;
        this.nextSeq = nextSeq;
    }

    public boolean halted() {
        return halted;
    }

    @Override
    public void onEvent(InputRing.Slot slot, long sequence, boolean endOfBatch) {
        if (halted) {
            return;
        }
        try {
            EventEnvelope envelope = slot.envelope.withSeq(nextSeq);
            journal.append(envelope);
            slot.envelope = envelope;
            nextSeq++;
            if (endOfBatch) {
                journal.commit();
            }
        } catch (SQLException | RuntimeException failure) {
            halted = true;
            LOG.error("journal write failed; the process stops handling events and must be reopened", failure);
        }
    }
}
