package tantalum.kernel.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolResultBlock;

class TrimmingTest {

    private static final ReasoningBlock CLAUDE = new ReasoningBlock("claude", new Json.Str("x"));
    private static final ReasoningBlock OPENAI = new ReasoningBlock("openai", new Json.Str("y"));

    @Test
    void reasoningOfEveryProviderIsRemoved() {
        Message message = new Message(Role.ASSISTANT, List.of(CLAUDE, new TextBlock("keep"), OPENAI), false);
        Message trimmed = Trimming.trim(message, 100);
        assertEquals(List.of(new TextBlock("keep")), trimmed.content());
        assertEquals(Role.ASSISTANT, trimmed.role());
    }

    @Test
    void messageLeftEmptyIsDropped() {
        assertNull(Trimming.trim(new Message(Role.ASSISTANT, List.of(CLAUDE), false), 100));
        List<Message> all = Trimming.trimAll(List.of(new Message(Role.ASSISTANT, List.of(CLAUDE), false), Message.user("u")), 100);
        assertEquals(List.of(Message.user("u")), all);
    }

    @Test
    void shortResultsAreUntouched() {
        ToolResultBlock result = new ToolResultBlock("t", "small", false, null);
        assertSame(result, Trimming.truncate(result, 5));
        Message message = new Message(Role.USER, List.of(result), false);
        assertEquals(message, Trimming.trim(message, 5));
    }

    @Test
    void longResultsKeepHeadAndTailAtUtf8BoundariesAndAreAnnotated() {
        String text = "ab" + "你".repeat(100) + "END";
        ToolResultBlock cut = Trimming.truncate(new ToolResultBlock("t", text, true, "CODE"), 64);
        assertTrue(cut.text().startsWith("ab"), cut.text());
        assertTrue(cut.text().endsWith("END"), cut.text());
        assertTrue(cut.text().contains(" of " + text.getBytes(StandardCharsets.UTF_8).length + " bytes truncated"), cut.text());
        assertTrue(cut.error());
        assertEquals("CODE", cut.code());
        assertEquals("t", cut.toolCallId());
        assertFalse(cut.text().contains("\uFFFD"));
    }

    /** 需求 1.7：总长含标记不超上限；再次截断不嵌套，保留最初的原长度。 */
    @Test
    void lengthIncludingTheMarkerStaysWithinTheLimit() {
        ToolResultBlock cut = Trimming.truncate(new ToolResultBlock("t", "x".repeat(10_000), false, null), 200);
        assertTrue(cut.text().getBytes(StandardCharsets.UTF_8).length <= 200, cut.text().length() + "");
    }

    @Test
    void truncatingAgainDoesNotNestMarkersAndKeepsTheOriginalLength() {
        ToolResultBlock once = Trimming.truncate(new ToolResultBlock("t", "x".repeat(5_000) + "END", false, null), 500);
        ToolResultBlock twice = Trimming.truncate(once, 200);
        assertEquals(1, twice.text().split("bytes truncated", -1).length - 1, twice.text());
        assertTrue(twice.text().contains(" of 5003 bytes truncated"), twice.text());
        assertTrue(twice.text().endsWith("END"), twice.text());
        assertTrue(twice.text().getBytes(StandardCharsets.UTF_8).length <= 200);
        assertSame(twice, Trimming.truncate(twice, 200), "already within the limit");
    }

    @Test
    void limitSmallerThanTheMarkerLeavesOnlyTheMarker() {
        ToolResultBlock cut = Trimming.truncate(new ToolResultBlock("t", "x".repeat(500), false, null), 8);
        assertTrue(cut.text().contains("of 500 bytes truncated"), cut.text());
        assertFalse(cut.text().contains("xx"), cut.text());
    }

    @Test
    void truncationKeepsTheLastLineOfALongLog() {
        String log = "line\n".repeat(2000) + "SECRET CODE: KESTREL-4137\n";
        ToolResultBlock cut = Trimming.truncate(new ToolResultBlock("t", log, false, null), 4096);
        assertTrue(cut.text().contains("KESTREL-4137"));
        assertTrue(cut.text().startsWith("line\nline\n"));
        assertTrue(cut.text().getBytes(StandardCharsets.UTF_8).length <= 4096);
    }

    @Test
    void interruptedFlagAndTextSurvive() {
        Message message = new Message(Role.ASSISTANT, List.of(new TextBlock("partial")), true);
        assertEquals(message, Trimming.trim(message, 1));
    }
}
