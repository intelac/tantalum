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
    void longResultsAreCutAtAUtf8BoundaryAndAnnotated() {
        String text = "ab" + "你".repeat(10);
        ToolResultBlock result = new ToolResultBlock("t", text, true, "CODE");
        ToolResultBlock cut = Trimming.truncate(result, 6);
        String[] parts = cut.text().split("\n", 2);
        assertEquals("ab你", parts[0], "cutting inside 好 would leave a broken character");
        assertEquals("[truncated: " + text.getBytes(StandardCharsets.UTF_8).length + " bytes total]", parts[1]);
        assertTrue(cut.error());
        assertEquals("CODE", cut.code());
        assertEquals("t", cut.toolCallId());
        assertFalse(cut.text().contains("�"));
    }

    @Test
    void interruptedFlagAndTextSurvive() {
        Message message = new Message(Role.ASSISTANT, List.of(new TextBlock("partial")), true);
        assertEquals(message, Trimming.trim(message, 1));
    }
}
