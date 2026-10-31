package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.Stop;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.KernelState;
import tantalum.value.state.PrincipalState;

class PolicyHooksTest {

    private static final Principal USER = new Principal("u");
    private static final Instant TODAY = Instant.parse("2026-10-31T12:00:00Z");
    private static final HookContext CONTEXT = HookContext.of(new RunRef(USER, new SessionId("s"), new RunId("r")), TODAY);

    private static KernelState used(LocalDate day, long tokens) {
        return KernelState.empty().with(PrincipalState.start(USER).add(day, new TokenUsage(tokens, 0, 0, 0)));
    }

    @Test
    void quotaAllowsUnderTheLimit() {
        assertSame(Allow.PLAIN, new DailyQuotaHook(100).beforeModelCall(used(LocalDate.of(2026, 10, 31), 99), CONTEXT));
    }

    @Test
    void quotaStopsAtTheLimit() {
        assertInstanceOf(Stop.class, new DailyQuotaHook(100).beforeModelCall(used(LocalDate.of(2026, 10, 31), 100), CONTEXT));
    }

    @Test
    void quotaResetsOnANewDay() {
        assertSame(Allow.PLAIN, new DailyQuotaHook(100).beforeModelCall(used(LocalDate.of(2026, 10, 30), 500), CONTEXT));
    }

    @Test
    void quotaAllowsAUserWithNoHistory() {
        assertSame(Allow.PLAIN, new DailyQuotaHook(100).beforeModelCall(KernelState.empty(), CONTEXT));
    }

    @Test
    void policiesAreMandatory() {
        assertEquals(true, new DailyQuotaHook(1).mandatory());
        assertEquals(true, new ReadOnlyHook(Set.of()).mandatory());
        assertEquals("dailyQuota", new DailyQuotaHook(1).name());
        assertEquals("readOnly", new ReadOnlyHook(Set.of()).name());
    }

    @Test
    void readOnlyBlocksEverythingNotListed() {
        ReadOnlyHook hook = new ReadOnlyHook(Set.of("grep"));
        assertSame(Allow.PLAIN, hook.beforeToolCall(KernelState.empty(),
                CONTEXT.withCall(new ToolCallBlock("c", "grep", "{}", new Json.Null()))));
        assertInstanceOf(Stop.class, hook.beforeToolCall(KernelState.empty(),
                CONTEXT.withCall(new ToolCallBlock("c", "write_file", "{}", new Json.Null()))));
    }
}
