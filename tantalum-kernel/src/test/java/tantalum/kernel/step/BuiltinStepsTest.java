package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.table.TransitionTable;

class BuiltinStepsTest {

    private static final List<List<String>> ROWS = List.of(
            List.of("AwaitingContext", "ContextAssembled"),
            List.of("AwaitingModel", "ModelReplied"),
            List.of("AwaitingModel", "ModelFailed"),
            List.of("AwaitingTools", "ToolFinished"),
            List.of("AwaitingRetry", "TimerFired"),
            List.of("AwaitingWork", "WorkDone"),
            List.of("AwaitingTools", "Cancel"),
            // 建会话，和对会话说话的三种输入：哪个状态下到都有行
            List.of("New", "SessionCreated"),
            List.of("New", "UserPrompt"),
            List.of("AwaitingModel", "UserPrompt"),
            List.of("New", "Assignment"),
            List.of("AwaitingModel", "Report"),
            List.of("Ended", "Report"),
            List.of("AwaitingModel", "QueueChanged"),
            // 交代：你要停、交代的一方不要了、期限到（没人等的定时器）
            List.of("Ended", "StopRequested"),
            List.of("AwaitingModel", "AssignmentCancelled"),
            List.of("Ended", "TimerFired"),
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
