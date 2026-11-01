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
        String text = "ab" + "你".repeat(10) + "END";
        ToolResultBlock result = new ToolResultBlock("t", text, true, "CODE");
        ToolResultBlock cut = Trimming.truncate(result, 8);
        String[] parts = cut.text().split("\n");
        assertEquals(3, parts.length, cut.text());
        assertEquals("ab", parts[0], "4 bytes of head: cutting inside the first 你 would leave a broken character, so it backs off");
        assertEquals("END", parts[2], "the tail survives: codes and conclusions sit at the end of tool output");
        int total = text.getBytes(StandardCharsets.UTF_8).length;
        assertEquals("[... " + (total - 2 - 3) + " of " + total + " bytes truncated ...]", parts[1]);
        assertTrue(cut.error());
        assertEquals("CODE", cut.code());
        assertEquals("t", cut.toolCallId());
        assertFalse(cut.text().contains("\uFFFD"));
    }

    @Test
    void truncationKeepsTheLastLineOfALongLog() {
        String log = "line\n".repeat(2000) + "SECRET CODE: KESTREL-4137\n";
        ToolResultBlock cut = Trimming.truncate(new ToolResultBlock("t", log, false, null), 4096);
        assertTrue(cut.text().contains("KESTREL-4137"));
        assertTrue(cut.text().startsWith("line\nline\n"));
        assertTrue(cut.text().getBytes(StandardCharsets.UTF_8).length < 4096 + 80);
    }

    @Test
    void interruptedFlagAndTextSurvive() {
        Message message = new Message(Role.ASSISTANT, List.of(new TextBlock("partial")), true);
        assertEquals(message, Trimming.trim(message, 1));
    }
}
