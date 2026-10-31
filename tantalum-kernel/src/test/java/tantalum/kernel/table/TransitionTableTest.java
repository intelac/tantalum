package tantalum.kernel.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import tantalum.value.decision.Decision;

class TransitionTableTest {

    private static final StepFunction STEP = context -> Decision.unchanged(context.state());

    @Test
    void findsARegisteredRow() {
        TransitionTable table = new TransitionTable();
        table.register("AwaitingModel", "ModelReplied", STEP);
        assertSame(STEP, table.find("AwaitingModel", "ModelReplied").orElseThrow());
    }

    @Test
    void unknownRowIsEmpty() {
        assertEquals(Optional.empty(), new TransitionTable().find("AwaitingModel", "ModelReplied"));
    }

    @Test
    void anyStateRowIsAFallback() {
        TransitionTable table = new TransitionTable();
        table.register(TransitionTable.ANY_STATE, "Cancel", STEP);
        assertSame(STEP, table.find("AwaitingTools", "Cancel").orElseThrow());
    }

    @Test
    void exactRowWinsOverAnyState() {
        StepFunction exact = context -> Decision.unchanged(context.state());
        TransitionTable table = new TransitionTable();
        table.register(TransitionTable.ANY_STATE, "Cancel", STEP);
        table.register("AwaitingTools", "Cancel", exact);
        assertSame(exact, table.find("AwaitingTools", "Cancel").orElseThrow());
    }

    @Test
    void duplicateRowFails() {
        TransitionTable table = new TransitionTable();
        table.register("AwaitingModel", "ModelReplied", STEP);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> table.register("AwaitingModel", "ModelReplied", STEP));
        assertTrue(failure.getMessage().contains("AwaitingModel"));
        assertTrue(failure.getMessage().contains("ModelReplied"));
    }
}
