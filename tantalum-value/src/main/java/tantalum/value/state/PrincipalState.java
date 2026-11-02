package tantalum.value.state;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import tantalum.value.id.Principal;
import tantalum.value.model.TokenUsage;

/** 一个用户的累计数据。钩子只读这里，日期取自事件时间。 */
public record PrincipalState(Principal principal, Map<LocalDate, TokenUsage> dailyUsage) {

    public PrincipalState {
        Objects.requireNonNull(principal, "principal");
        dailyUsage = Map.copyOf(dailyUsage);
    }

    public static PrincipalState start(Principal principal) {
        return new PrincipalState(principal, Map.of());
    }

    public TokenUsage usageOn(LocalDate day) {
        return dailyUsage.getOrDefault(day, TokenUsage.ZERO);
    }

    public PrincipalState add(LocalDate day, TokenUsage usage) {
        Map<LocalDate, TokenUsage> updated = new HashMap<>(dailyUsage);
        updated.put(day, usageOn(day).plus(usage));
        return new PrincipalState(principal, updated);
    }
}
