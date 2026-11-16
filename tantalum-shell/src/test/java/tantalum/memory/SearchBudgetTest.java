package tantalum.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.plugin.ToolContext;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/** 2026-11-16 评测：一轮里找记忆超过 8 次就不再搜，回一句“用找到的回答”；下一轮重新算，别的人、别的会话各算各的。 */
class SearchBudgetTest {

    @TempDir
    Path dir;

    private ToolContext context(String user, String run) {
        try {
            ToolContext plain = new ToolContext(new Principal(user), new SessionId("s-1"), new tantalum.shell.plugin.Workspace(dir));
            return run == null ? plain : plain.in(new RunId(run), new CommandId("c"), false);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void theNinthLookInOneTurnIsTurnedAway() {
        SearchBudget budget = new SearchBudget();
        for (int i = 0; i < SearchBudget.PER_TURN; i++) {
            assertTrue(budget.spend(context("alice", "s-1_1")).isEmpty(), "look " + (i + 1));
        }
        assertEquals(SearchBudget.SPENT, budget.spend(context("alice", "s-1_1")).orElseThrow());
        assertTrue(budget.spend(context("alice", "s-1_2")).isEmpty(), "the next turn starts again");
        assertTrue(budget.spend(context("bob", "s-1_1")).isEmpty(), "someone else's turn is counted apart");
        for (int i = 0; i < 20; i++) {
            assertTrue(budget.spend(context("alice", null)).isEmpty(), "outside a run nothing is counted");
        }
    }
}
