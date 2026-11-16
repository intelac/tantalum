package tantalum.value.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tantalum.value.event.DelegateReturned;
import tantalum.value.event.Delegated;
import tantalum.value.id.CommandId;
import tantalum.value.id.SessionId;

/**
 * 这个会话在委派里的位置（kernel-delegation），记在会话状态里、随账落盘，不要旁表。{@code parent} 是交活给它的父会话（普通会话为 null），
 * {@code depth} 是第几层（普通会话 0）。{@code queued} 是会话正忙时收到、还没开始的交活，按到的顺序。{@code returned} 是最近
 * {@value #KEEP} 次交活的结局：同一个委派号再送来时据此把交回再发一次，不重做。{@code tools} 是交活限定的工具（null 是不限定）。
 */
public record Handoffs(SessionId parent, int depth, List<Delegated> queued, List<DelegateReturned> returned, List<String> tools) {

    public static final int KEEP = 8;
    public static final Handoffs NONE = new Handoffs(null, 0, List.of(), List.of(), null);

    public Handoffs {
        queued = queued == null ? List.of() : List.copyOf(queued);
        returned = returned == null ? List.of() : List.copyOf(returned);
        tools = tools == null ? null : List.copyOf(tools);
    }

    /** 是不是交活开出来的子会话。 */
    public boolean isChild() {
        return parent != null;
    }

    /** 第一次收到交活时定下父会话、层数和限定的工具；之后不变。 */
    public Handoffs under(SessionId newParent, int newDepth, List<String> newTools) {
        return parent != null ? this : new Handoffs(newParent, newDepth, queued, returned, newTools);
    }

    public Handoffs enqueue(Delegated handoff) {
        List<Delegated> grown = new ArrayList<>(queued);
        grown.add(handoff);
        return new Handoffs(parent, depth, grown, returned, tools);
    }

    /** 去掉排在最前面的那次交活。 */
    public Handoffs dequeue() {
        return new Handoffs(parent, depth, queued.subList(1, queued.size()), returned, tools);
    }

    /** 记下一次交活的结局；同一个委派号的旧结局被换掉（先交回“问题”、后交回“结果”），只留最近 {@value #KEEP} 个。 */
    public Handoffs settle(DelegateReturned outcome) {
        List<DelegateReturned> kept = new ArrayList<>(returned);
        kept.removeIf(old -> old.delegation().equals(outcome.delegation()));
        kept.add(outcome);
        while (kept.size() > KEEP) {
            kept.removeFirst();
        }
        return new Handoffs(parent, depth, queued, kept, tools);
    }

    public Optional<DelegateReturned> outcomeOf(CommandId delegation) {
        return returned.stream().filter(outcome -> outcome.delegation().equals(delegation)).findFirst();
    }

    public boolean isQueued(CommandId delegation) {
        return queued.stream().anyMatch(handoff -> handoff.delegation().equals(delegation));
    }
}
