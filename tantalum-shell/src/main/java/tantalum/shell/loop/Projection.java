package tantalum.shell.loop;

import java.util.List;
import java.util.Map;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.value.decision.Decision;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.SessionId;

/** 事件的展示投影。{@link Cleanroom} 每处理完一个事件调用一次，返回这次变化的条目。只改内存，必须立即返回。 */
@FunctionalInterface
public interface Projection {

    Projection NONE = (envelope, decision) -> List.of();

    List<TranscriptItem> apply(EventEnvelope envelope, Decision decision);

    /** 一个会话的完整内部状态，写快照用。 */
    default Transcript.Part export(SessionId session) {
        return new Transcript.Part(List.of(), List.of(), Map.of(), 0, -1);
    }

    default void restore(SessionId session, Transcript.Part part) {
    }

    default void remove(SessionId session) {
    }
}
