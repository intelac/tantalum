package tantalum.kernel.hook;

import java.util.List;
import tantalum.value.decision.Fact;
import tantalum.value.hook.HookResult;

/** 遍历一个钩子位置的结果，以及要上报的事实。{@code decidedBy} 是做出拦截或要求工作的那个钩子，放行时为 null。 */
public record HookOutcome(HookResult result, List<Fact> facts, String decidedBy) {

    public HookOutcome {
        facts = List.copyOf(facts);
    }
}
