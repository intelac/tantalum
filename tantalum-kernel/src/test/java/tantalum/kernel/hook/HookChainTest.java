package tantalum.kernel.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.decision.HookReported;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.PromptPatch;
import tantalum.value.hook.Stop;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.state.KernelState;

class HookChainTest {

    private static final HookContext CONTEXT = HookContext.of(
            new RunRef(new Principal("p"), new SessionId("s"), new RunId("r")), Instant.EPOCH);

    private final List<String> calls = new ArrayList<>();

    private KernelHook hook(String name, boolean mandatory, HookResult result) {
        return new KernelHook() {
            @Override public String name() { return name; }
            @Override public boolean mandatory() { return mandatory; }
            @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
                calls.add(name);
                return result;
            }
            @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
                calls.add(name);
                return result;
            }
        };
    }

    private KernelHook throwing(String name, boolean mandatory) {
        return new KernelHook() {
            @Override public String name() { return name; }
            @Override public boolean mandatory() { return mandatory; }
            @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
                throw new IllegalStateException("boom");
            }
            @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
                throw new IllegalStateException("boom");
            }
        };
    }

    private HookOutcome modelCall(KernelHook... hooks) {
        return new HookChain(List.of(hooks)).run(HookPoint.BEFORE_MODEL_CALL, KernelState.empty(), CONTEXT);
    }

    @Test
    void hooksRunInRegistrationOrder() {
        HookOutcome outcome = modelCall(hook("a", false, Allow.PLAIN), hook("b", false, Allow.PLAIN));
        assertEquals(List.of("a", "b"), calls);
        assertInstanceOf(Allow.class, outcome.result());
    }

    @Test
    void stopSkipsLaterHooks() {
        HookOutcome outcome = modelCall(hook("a", false, new Stop("quota")), hook("b", false, Allow.PLAIN));
        assertEquals(List.of("a"), calls);
        assertEquals(new Stop("quota"), outcome.result());
    }

    @Test
    void needWorkSkipsLaterHooks() {
        NeedWork work = new NeedWork("citation", new Json.Null());
        HookOutcome outcome = modelCall(hook("a", false, work), hook("b", false, Allow.PLAIN));
        assertEquals(List.of("a"), calls);
        assertEquals(work, outcome.result());
    }

    @Test
    void allowPatchesAreMergedInOrder() {
        HookOutcome outcome = modelCall(
                hook("a", false, new Allow(new PromptPatch("one", List.of()))),
                hook("b", false, new Allow(new PromptPatch("two", List.of()))));
        assertEquals(new Allow(new PromptPatch("onetwo", List.of())), outcome.result());
    }

    @Test
    void mandatoryExceptionStopsAndIsReported() {
        HookOutcome outcome = modelCall(throwing("quota", true), hook("b", false, Allow.PLAIN));
        assertInstanceOf(Stop.class, outcome.result());
        assertEquals(List.of(), calls);
        HookReported reported = assertInstanceOf(HookReported.class, outcome.facts().getFirst());
        assertEquals("quota", reported.hook());
    }

    @Test
    void optionalExceptionIsSkippedAndReported() {
        HookOutcome outcome = modelCall(throwing("metrics", false), hook("b", false, Allow.PLAIN));
        assertInstanceOf(Allow.class, outcome.result());
        assertEquals(List.of("b"), calls);
        assertEquals(1, outcome.facts().size());
    }

    /** 返回 null 违反契约：不能当作放行，也不能让后面的钩子被跳过而无人知道。 */
    @Test
    void nullResultIsTreatedAsStopAndReported() {
        HookOutcome outcome = modelCall(hook("broken", false, null), hook("b", false, Allow.PLAIN));
        Stop stop = assertInstanceOf(Stop.class, outcome.result());
        assertTrue(stop.reason().contains("broken"));
        assertEquals(List.of("broken"), calls);
        assertEquals("broken", assertInstanceOf(HookReported.class, outcome.facts().getFirst()).hook());
    }

    @Test
    void beforeToolCallExceptionAlwaysBlocks() {
        HookOutcome outcome = new HookChain(List.of(throwing("metrics", false)))
                .run(HookPoint.BEFORE_TOOL_CALL, KernelState.empty(), CONTEXT);
        assertInstanceOf(Stop.class, outcome.result());
        assertTrue(!outcome.facts().isEmpty());
    }
}
