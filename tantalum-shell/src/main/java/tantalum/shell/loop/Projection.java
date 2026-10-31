package tantalum.shell.loop;

import java.util.List;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.value.decision.Decision;
import tantalum.value.event.EventEnvelope;

/** 事件的展示投影。{@link Cleanroom} 每处理完一个事件调用一次，返回这次变化的条目。只改内存，必须立即返回。 */
@FunctionalInterface
public interface Projection {

    Projection NONE = (envelope, decision) -> List.of();

    List<TranscriptItem> apply(EventEnvelope envelope, Decision decision);
}
