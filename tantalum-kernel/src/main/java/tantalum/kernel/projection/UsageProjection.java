package tantalum.kernel.projection;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import tantalum.value.id.Principal;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.KernelState;
import tantalum.value.state.PrincipalState;

/** 把 token 用量累加到用户状态。日期按事件时间的 UTC 计算，保证回放结果相同。 */
public final class UsageProjection {

    private UsageProjection() {}

    public static KernelState apply(KernelState state, Principal principal, Instant at, TokenUsage usage) {
        if (usage == null) {
            return state;
        }
        PrincipalState current = state.principals().get(principal);
        if (current == null) {
            current = PrincipalState.start(principal);
        }
        return state.with(current.add(LocalDate.ofInstant(at, ZoneOffset.UTC), usage));
    }
}
