package tantalum.kernel.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.id.CommandId;
import tantalum.value.json.Json;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.ToolRound;
import tantalum.value.state.ToolSlot;

class ToolPairingTest {

    private static ToolCallBlock call(String id) {
        return new ToolCallBlock(id, "t", "{}", new Json.Null());
    }

    @Test
    void everyCallGetsExactlyOneResultInOrder() {
        ToolResultBlock real = new ToolResultBlock("done", "out", false, null);
        ToolRound round = new ToolRound(List.of(
                ToolSlot.pending(call("done"), true).sent(new CommandId("1")).done(real),
                ToolSlot.pending(call("sent"), true).sent(new CommandId("2")),
                ToolSlot.pending(call("waiting"), true)), 3);

        List<ToolResultBlock> results = ToolPairing.complete(round);

        assertEquals(List.of("done", "sent", "waiting"), results.stream().map(ToolResultBlock::toolCallId).toList());
        assertEquals(real, results.get(0));
        assertEquals(ToolPairing.OUTCOME_UNKNOWN, results.get(1).code());
        assertEquals(ToolPairing.ABORTED_BEFORE_DISPATCH, results.get(2).code());
        assertTrue(results.get(1).error());
        assertTrue(results.get(2).error());
    }

    @Test
    void unknownOutcomeWarnsTheModelNotToRepeatBlindly() {
        ToolRound round = new ToolRound(List.of(ToolSlot.pending(call("a"), true).sent(new CommandId("1"))), 1);
        assertTrue(ToolPairing.complete(round).getFirst().text().contains("idempotent"));
    }
}
