package tantalum.kernel.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.state.ContextSection;

class ContextDiffTest {

    private static ContextSection index(String body) {
        return new ContextSection("memory-index", body, true);
    }

    private static ContextSection knowledge(String body) {
        return new ContextSection("shared-knowledge", body, false);
    }

    @Test
    void nothingChangedMeansNoMessage() {
        assertEquals("", ContextDiff.of(List.of(index("a"), knowledge("k")), List.of(index("a"), knowledge("k"))));
    }

    /** 需求 3.1：一条记忆变化只发那一行，共享知识不重发。 */
    @Test
    void aMemoryChangeSendsOnlyTheChangedLines() {
        String big = "K".repeat(50_000);
        String diff = ContextDiff.of(List.of(knowledge(big), index("- keep\n- old")), List.of(knowledge(big), index("- keep\n- new")));
        assertTrue(diff.contains("Added:\n- new"), diff);
        assertTrue(diff.contains("Removed:\n- old"), diff);
        assertFalse(diff.contains("- keep"), diff);
        assertFalse(diff.contains("KKK"), diff);
    }

    @Test
    void aChangedNonLineSectionIsResentWhole() {
        String diff = ContextDiff.of(List.of(knowledge("old")), List.of(knowledge("new")));
        assertTrue(diff.contains("<shared-knowledge>\nnew\n</shared-knowledge>"), diff);
    }

    @Test
    void aVanishedSectionGetsARemovalNoteAndANewOneIsSent() {
        String diff = ContextDiff.of(List.of(index("- a")), List.of(knowledge("k")));
        assertTrue(diff.contains("memory-index no longer applies"), diff);
        assertTrue(diff.contains("<shared-knowledge>\nk\n</shared-knowledge>"), diff);
    }
}
