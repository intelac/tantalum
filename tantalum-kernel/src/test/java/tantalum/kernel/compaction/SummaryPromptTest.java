package tantalum.kernel.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;

class SummaryPromptTest {

    private static String text(Message message) {
        return ((TextBlock) message.content().getFirst()).text();
    }

    @Test
    void instructionHasTheFixedSectionsAndOptionalNotes() {
        String plain = text(SummaryPrompt.instruction(false, null));
        for (String section : List.of("## Goal", "## Constraints & preferences", "## Progress", "### Done", "### In progress",
                "### Blocked", "## Key decisions", "## Next steps", "## Key context")) {
            assertTrue(plain.contains(section), section);
        }
        assertTrue(plain.contains("Copy them exactly"));
        assertTrue(plain.contains("earlier summary"), "re-compaction must update the previous summary");
        assertTrue(plain.contains("Do not call any tool"));
        assertFalse(plain.contains("omitted"));
        assertFalse(plain.contains("particular attention"));

        String dropped = text(SummaryPrompt.instruction(true, "  remember the test results  "));
        assertTrue(dropped.contains("has been omitted"));
        assertTrue(dropped.endsWith("remember the test results"));
        assertEquals(plain, text(SummaryPrompt.instruction(false, "   ")), "blank manual notes add nothing");
    }

    /** 需求 1.6：两节新内容、忠实保留纠正、前缀让模型直接继续。 */
    @Test
    void instructionCoversCodeErrorsAndCorrectionsAndThePrefixResumes() {
        String plain = text(SummaryPrompt.instruction(false, null));
        for (String part : List.of("## Files and code", "## Errors and fixes", "corrected", "from In progress to Done")) {
            assertTrue(plain.contains(part), part);
        }
        assertTrue(SummaryPrompt.SUMMARY_PREFIX.contains("Continue the task directly"));
    }

    @Test
    void summaryMessageIsRecognisable() {
        Message summary = SummaryPrompt.summaryMessage("  Goal: x\n");
        assertTrue(SummaryPrompt.isSummary(summary));
        assertEquals(SummaryPrompt.SUMMARY_PREFIX + "Goal: x", text(summary));
        assertEquals(List.of("a", "b"), SummaryPrompt.filesIn(SummaryPrompt.summaryMessage("s", List.of("a", "b"))));
        assertEquals(List.of(), SummaryPrompt.filesIn(summary));
        assertFalse(SummaryPrompt.isSummary(Message.user("Goal: x")));
    }

    @Test
    void validationRejectsEachFailure() {
        List<ContentBlock> good = List.of(new TextBlock("Goal: short"));
        assertNull(SummaryPrompt.validate(good, StopReason.END_TURN, 10_000, 0));
        assertEquals("summary did not end normally: MAX_TOKENS", SummaryPrompt.validate(good, StopReason.MAX_TOKENS, 10_000, 0));
        assertEquals("summary tried to call a tool", SummaryPrompt.validate(
                List.of(new TextBlock("x"), new ToolCallBlock("t", "grep", "{}", new Json.Obj(Map.of()))), StopReason.END_TURN, 10_000, 0));
        assertEquals("summary is empty", SummaryPrompt.validate(List.of(new TextBlock("  \n")), StopReason.END_TURN, 10_000, 0));
        assertEquals("summary is empty", SummaryPrompt.validate(List.of(), StopReason.END_TURN, 10_000, 0));
        long size = TokenEstimate.ofMessage(SummaryPrompt.summaryMessage("Goal: short"));
        assertEquals("summary is not smaller than what it replaces", SummaryPrompt.validate(good, StopReason.END_TURN, size, 0));
        assertNull(SummaryPrompt.validate(good, StopReason.END_TURN, size + 1, 0));
        assertEquals("summary is not smaller than what it replaces", SummaryPrompt.validate(good, StopReason.END_TURN, size + 1, 1),
                "the request repeated after the summary counts too");
    }

    @Test
    void textJoinsOnlyTextBlocks() {
        assertEquals("ab", SummaryPrompt.text(List.of(new TextBlock("a"),
                new ToolCallBlock("t", "grep", "{}", new Json.Obj(Map.of())), new TextBlock("b"))));
    }
}
