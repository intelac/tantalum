package tantalum.value.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.json.Json;

class ModelTypesTest {

    private static final Json EMPTY = new Json.Arr(List.of());

    @Test
    void messageCopiesContent() {
        List<ContentBlock> content = new ArrayList<>(List.of(new TextBlock("a")));
        Message message = new Message(Role.ASSISTANT, content, false);
        content.clear();
        assertEquals(1, message.content().size());
    }

    @Test
    void userMessageHoldsText() {
        assertEquals(List.of(new TextBlock("hi")), Message.user("hi").content());
        assertFalse(Message.user("hi").interrupted());
    }

    @Test
    void toolCallWithoutParsedArgumentsIsInvalid() {
        assertFalse(new ToolCallBlock("1", "grep", "{oops", null).argumentsValid());
        assertTrue(new ToolCallBlock("1", "grep", "[]", EMPTY).argumentsValid());
    }

    @Test
    void reasoningKeepsProviderAndRawContent() {
        ReasoningBlock block = new ReasoningBlock("claude", new Json.Str("signed"));
        assertEquals("claude", block.provider());
        assertEquals(new Json.Str("signed"), block.raw());
    }

    @Test
    void requiredFieldsAreChecked() {
        assertThrows(NullPointerException.class, () -> new TextBlock(null));
        assertThrows(NullPointerException.class, () -> new ReasoningBlock(null, EMPTY));
        assertThrows(NullPointerException.class, () -> new ReasoningBlock("p", null));
        assertThrows(NullPointerException.class, () -> new ToolCallBlock(null, "n", "", EMPTY));
        assertThrows(NullPointerException.class, () -> new ToolCallBlock("i", null, "", EMPTY));
        assertThrows(NullPointerException.class, () -> new ToolCallBlock("i", "n", null, EMPTY));
        assertThrows(NullPointerException.class, () -> new ToolResultBlock(null, "", false, null));
        assertThrows(NullPointerException.class, () -> new ToolResultBlock("i", null, false, null));
        assertThrows(NullPointerException.class, () -> new Message(null, List.of(), false));
        assertThrows(NullPointerException.class, () -> new ToolSpec(null, "", EMPTY));
        assertThrows(NullPointerException.class, () -> new ToolSpec("n", null, EMPTY));
        assertThrows(NullPointerException.class, () -> new ToolSpec("n", "", null));
        assertThrows(NullPointerException.class, () -> new Prompt(null, List.of(), List.of()));
    }

    @Test
    void promptCopiesLists() {
        List<Message> messages = new ArrayList<>(List.of(Message.user("a")));
        Prompt prompt = new Prompt("s", List.of(new ToolSpec("t", "d", EMPTY)), messages);
        messages.clear();
        assertEquals(1, prompt.messages().size());
        assertEquals(1, prompt.tools().size());
    }

    @Test
    void negativeUsageIsRejected() {
        assertEquals(3, new TokenUsage(3, 1, 0, 0).input());
        assertThrows(IllegalArgumentException.class, () -> new TokenUsage(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenUsage(0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenUsage(0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenUsage(0, 0, 0, -1));
    }
}
