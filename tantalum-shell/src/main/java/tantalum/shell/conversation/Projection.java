package tantalum.shell.conversation;

import java.util.List;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.value.decision.Decision;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.SessionId;
import tantalum.value.state.KernelState;

/** 事件的展示投影。{@link Cleanroom} 每处理完一个事件调用一次，返回这次变化的条目。只改内存，必须立即返回。 */
@FunctionalInterface
public interface Projection {

    List<TranscriptItem> apply(EventEnvelope envelope, Decision decision);

    /** 带处理前的状态：要比较前后才知道这一步追加了什么（工具变化）时用它。 */
    default List<TranscriptItem> apply(EventEnvelope envelope, KernelState before, Decision decision) {
        return apply(envelope, decision);
    }

    default void restore(SessionId session, Transcript.Saved saved) {
    }

    default void remove(SessionId session) {
    }
}
