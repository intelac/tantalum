package tantalum.kernel.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tantalum.value.id.Principal;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.KernelState;

class UsageProjectionTest {

    private static final Principal USER = new Principal("u");
    private static final Principal OTHER = new Principal("other");

    @Test
    void addsUsageToThePrincipalAndEventDay() {
        KernelState state = UsageProjection.apply(KernelState.empty(), USER,
                Instant.parse("2026-10-31T23:59:00Z"), new TokenUsage(1, 2, 0, 0));
        state = UsageProjection.apply(state, USER, Instant.parse("2026-10-31T00:00:00Z"), new TokenUsage(1, 1, 0, 0));
        state = UsageProjection.apply(state, OTHER, Instant.parse("2026-10-31T00:00:00Z"), new TokenUsage(9, 9, 0, 0));

        assertEquals(new TokenUsage(2, 3, 0, 0), state.principals().get(USER).usageOn(LocalDate.of(2026, 10, 31)));
        assertEquals(new TokenUsage(9, 9, 0, 0), state.principals().get(OTHER).usageOn(LocalDate.of(2026, 10, 31)));
    }

    @Test
    void missingUsageChangesNothing() {
        KernelState state = KernelState.empty();
        assertSame(state, UsageProjection.apply(state, USER, Instant.EPOCH, null));
    }
}
