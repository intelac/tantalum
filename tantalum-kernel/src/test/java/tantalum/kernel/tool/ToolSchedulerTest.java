package tantalum.kernel.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.id.CommandId;
import tantalum.value.json.Json;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.ToolRound;
import tantalum.value.state.ToolSlot;

class ToolSchedulerTest {

    private static ToolCallBlock call(String id) {
        return new ToolCallBlock(id, "t", "{}", new Json.Null());
    }

    private static ToolSlot safe(String id) {
        return ToolSlot.pending(call(id), true);
    }

    private static ToolSlot exclusive(String id) {
        return ToolSlot.pending(call(id), false);
    }

    private static ToolSlot sent(ToolSlot slot) {
        return slot.sent(new CommandId("c"));
    }

    private static ToolSlot done(ToolSlot slot) {
        return slot.sent(new CommandId("c")).done(new ToolResultBlock(slot.call().id(), "", false, null));
    }

    private static List<Integer> next(int max, ToolSlot... slots) {
        return ToolScheduler.next(new ToolRound(List.of(slots), slots.length), max);
    }

    @Test
    void consecutiveSafeCallsGoTogether() {
        assertEquals(List.of(0, 1, 2), next(10, safe("a"), safe("b"), safe("c")));
    }

    @Test
    void limitCapsInFlightCalls() {
        assertEquals(List.of(0, 1), next(2, safe("a"), safe("b"), safe("c")));
        assertEquals(List.of(2), next(2, done(safe("a")), sent(safe("b")), safe("c"), safe("d")));
        assertEquals(List.of(), next(1, sent(safe("a")), safe("b")));
    }

    @Test
    void exclusiveCallRunsAlone() {
        assertEquals(List.of(0), next(10, exclusive("a"), safe("b")));
        assertEquals(List.of(), next(10, sent(exclusive("a")), safe("b")));
    }

    @Test
    void exclusiveCallIsABarrier() {
        assertEquals(List.of(0), next(10, safe("a"), exclusive("b"), safe("c")));
        assertEquals(List.of(), next(10, sent(safe("a")), exclusive("b"), safe("c")));
        assertEquals(List.of(1), next(10, done(safe("a")), exclusive("b"), safe("c")));
        assertEquals(List.of(2, 3), next(10, done(safe("a")), done(exclusive("b")), safe("c"), safe("d")));
    }

    @Test
    void blockedCallsAreSkipped() {
        ToolSlot blocked = ToolSlot.blocked(call("x"), new ToolResultBlock("x", "no", true, null));
        assertEquals(List.of(1), next(10, blocked, safe("a")));
    }

    @Test
    void uncheckedCallsAreNotScheduled() {
        ToolRound round = new ToolRound(List.of(safe("a"), safe("b")), 1);
        assertEquals(List.of(0), ToolScheduler.next(round, 10));
    }
}
