package tantalum.shell.loop;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.conversation.Projection;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.shell.trace.Trace;
import tantalum.shell.trace.TraceRecord;
import tantalum.value.decision.Decision;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.SessionId;
import tantalum.value.state.KernelState;

/**
 * 事件的两个展示投影：对话条目和轨迹记录。{@link Cleanroom} 实时调用，会话文件的回放（冷载入、SSE 补读）也走这里，
 * 所以两边编号相同。只改内存，必须立即返回。
 */
public final class Projections {

    private static final Logger LOG = LoggerFactory.getLogger(Projections.class);

    /** 一个事件带来的变化：先条目、后轨迹记录，推送时按这个顺序接在事实后面编号。 */
    public record Output(List<TranscriptItem> items, List<TraceRecord> records) {}

    private final Projection transcript;
    private final Trace trace;

    public Projections(Projection transcript, Trace trace) {
        this.transcript = transcript;
        this.trace = trace;
    }

    /** 某个投影出错只丢它这次的变化，另一个照常；投影错了不能影响运行。回放时同样出错、同样丢，编号仍和实时一致。 */
    public Output apply(EventEnvelope envelope, KernelState before, Decision decision) {
        List<TranscriptItem> items;
        try {
            items = transcript.apply(envelope, decision);
        } catch (RuntimeException failure) {
            LOG.error("transcript projection failed at seq {}", envelope.seq(), failure);
            items = List.of();
        }
        List<TraceRecord> records;
        try {
            records = trace.apply(envelope, before, decision);
        } catch (RuntimeException failure) {
            LOG.error("trace projection failed at seq {}", envelope.seq(), failure);
            records = List.of();
        }
        return new Output(items, records);
    }

    /** 冷载入的会话并入时恢复两个投影的状态。 */
    public void restore(SessionId session, SessionSlice slice) {
        transcript.restore(session, slice.transcript());
        trace.restore(session, slice.trace());
    }

    public void remove(SessionId session) {
        transcript.remove(session);
        trace.remove(session);
    }
}
