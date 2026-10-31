package tantalum.kernel.table;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BuiltinStepsTest {

    private static final List<List<String>> ROWS = List.of(
            List.of("AwaitingContext", "ContextAssembled"),
            List.of("AwaitingModel", "ModelReplied"),
            List.of("AwaitingModel", "ModelFailed"),
            List.of("AwaitingTools", "ToolFinished"),
            List.of("AwaitingRetry", "TimerFired"),
            List.of("AwaitingWork", "WorkDone"),
            List.of("AwaitingTools", "Cancel"),
            List.of("AwaitingModel", "SteerSubmitted"),
            List.of("New", "UserPrompt"),
            List.of("New", "FollowUpSubmitted"),
            List.of("Ended", "ModelInterrupted"));

    @Test
    void everyBuiltinRowExists() {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        for (List<String> row : ROWS) {
            assertTrue(table.find(row.get(0), row.get(1)).isPresent(), row.toString());
        }
    }

    @Test
    void pluginCannotOverrideABuiltinRow() {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        assertThrows(IllegalStateException.class, () -> table.register("AwaitingModel", "ModelReplied",
                context -> tantalum.value.decision.Decision.unchanged(context.state())));
    }
}
