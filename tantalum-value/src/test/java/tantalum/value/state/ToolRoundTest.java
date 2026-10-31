package tantalum.value.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.id.CommandId;
import tantalum.value.json.Json;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;

class ToolRoundTest {

    private static ToolCallBlock call(String id) {
        return new ToolCallBlock(id, "grep", "{}", new Json.Null());
    }

    private static ToolResultBlock result(String id) {
        return new ToolResultBlock(id, "out", false, null);
    }

    @Test
    void slotMovesThroughItsStatuses() {
        ToolSlot pending = ToolSlot.pending(call("a"), true);
        ToolSlot sent = pending.sent(new CommandId("1-0"));
        ToolSlot done = sent.done(result("a"));

        assertEquals(ToolSlot.Status.SENT, sent.status());
        assertEquals(new CommandId("1-0"), done.command());
        assertTrue(done.finished());
        assertFalse(sent.finished());
        assertTrue(done.concurrencySafe());
    }

    @Test
    void blockedSlotCountsAsFinished() {
        assertTrue(ToolSlot.blocked(call("a"), result("a")).finished());
    }

    @Test
    void resultsKeepModelOrder() {
        ToolRound round = new ToolRound(List.of(ToolSlot.pending(call("a"), true), ToolSlot.pending(call("b"), true)));
        assertFalse(round.allFinished());

        ToolRound updated = round
                .withSlot(1, round.slots().get(1).sent(new CommandId("1-1")).done(result("b")))
                .withSlot(0, round.slots().get(0).sent(new CommandId("1-0")).done(result("a")));

        assertTrue(updated.allFinished());
        assertEquals(List.of(result("a"), result("b")), updated.results());
        assertFalse(round.allFinished());
    }

    @Test
    void slotRequiresCallAndStatus() {
        assertThrows(NullPointerException.class, () -> new ToolSlot(null, ToolSlot.Status.PENDING, false, null, null));
        assertThrows(NullPointerException.class, () -> new ToolSlot(call("a"), null, false, null, null));
    }
}
