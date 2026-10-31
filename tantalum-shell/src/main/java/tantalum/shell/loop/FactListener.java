package tantalum.shell.loop;

import tantalum.shell.conversation.TranscriptItem;
import tantalum.value.decision.Fact;
import tantalum.value.id.Principal;

/**
 * 接收内核产生的事实和对话条目的变化。必须立即返回，不能阻塞。
 * {@code seq} 和 {@code index} 定位一条通知：同一事件里先数事实，再数条目，回放时相同。
 */
@FunctionalInterface
public interface FactListener {

    void onFact(long seq, int index, Fact fact);

    default void onItem(long seq, int index, Principal owner, TranscriptItem item) {
    }
}
