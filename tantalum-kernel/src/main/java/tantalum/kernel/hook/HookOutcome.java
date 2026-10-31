package tantalum.kernel.hook;

import java.util.List;
import tantalum.value.decision.Fact;
import tantalum.value.hook.HookResult;

/** 遍历一个钩子位置的结果，以及要上报的事实。 */
public record HookOutcome(HookResult result, List<Fact> facts) {

    public HookOutcome {
        facts = List.copyOf(facts);
    }
}
