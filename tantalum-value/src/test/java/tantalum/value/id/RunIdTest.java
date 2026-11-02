package tantalum.value.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RunIdTest {

    @Test
    void sessionAndSeqComeBackFromTheId() {
        RunId run = RunId.of(new SessionId("s-1a_b"), 42);
        assertEquals("s-1a_b_42", run.value());
        assertEquals(new SessionId("s-1a_b"), run.session().orElseThrow());
        assertEquals(42, run.seq());
    }

    @Test
    void sameSeqInDifferentSessionsGivesDifferentRuns() {
        assertNotEquals(RunId.of(new SessionId("a"), 5), RunId.of(new SessionId("b"), 5));
        assertNotEquals(RunId.of(new SessionId("a_1"), 5), RunId.of(new SessionId("a"), 15));
    }

    @Test
    void malformedIdsHaveNoSessionOrSeq() {
        for (String malformed : List.of("legacy", "run-12", "s_", "_5", "s_x", "s_-1", "s_1-0", "s_99999999999999999999", " _5")) {
            RunId run = new RunId(malformed);
            assertTrue(run.session().isEmpty(), malformed);
        }
        assertEquals(-1, new RunId("run-12").seq());
        assertEquals(-1, new RunId("s_x").seq());
        assertEquals(-1, new RunId("s_99999999999999999999").seq());
    }
}
