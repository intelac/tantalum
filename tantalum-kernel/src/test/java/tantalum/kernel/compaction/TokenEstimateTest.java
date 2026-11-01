package tantalum.kernel.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.model.ToolSpec;

class TokenEstimateTest {

    @Test
    void asciiIsBytesOverThreeRoundedUp() {
        assertEquals(0, TokenEstimate.ofText(""));
        assertEquals(0, TokenEstimate.ofText(null));
        assertEquals(1, TokenEstimate.ofText("a"));
        assertEquals(1, TokenEstimate.ofText("abc"));
        assertEquals(2, TokenEstimate.ofText("abcd"));
    }

    @Test
    void chineseCountsUtf8BytesNotCharacters() {
        assertEquals(3, TokenEstimate.ofText("你好你"), "three characters are nine bytes");
        assertTrue(TokenEstimate.ofText("你好") > TokenEstimate.ofText("hi"));
    }

    @Test
    void messageCountsEveryKindOfBlock() {
        Message message = new Message(Role.ASSISTANT, List.of(
                new TextBlock("abc"),
                new ReasoningBlock("claude", new Json.Obj(Map.of("thinking", new Json.Str("deep")))),
                new ToolCallBlock("t1", "grep", "{\"q\":\"x\"}", new Json.Obj(Map.of()))), false);
        long text = 1;
        long reasoning = TokenEstimate.ofText("{\"thinking\":\"deep\"}");
        long call = TokenEstimate.ofText("grep") + TokenEstimate.ofText("{\"q\":\"x\"}");
        assertEquals(text + reasoning + call, TokenEstimate.ofMessage(message));
        assertEquals(TokenEstimate.ofText("result"), TokenEstimate.ofMessage(
                new Message(Role.USER, List.of(new ToolResultBlock("t1", "result", false, null)), false)));
    }

    @Test
    void listsAddUp() {
        List<Message> messages = List.of(Message.user("abc"), Message.user("abcd"));
        assertEquals(3, TokenEstimate.ofMessages(messages));
        ToolSpec tool = new ToolSpec("grep", "search", new Json.Obj(Map.of("type", new Json.Str("object"))));
        assertEquals(TokenEstimate.ofText("grep") + TokenEstimate.ofText("search") + TokenEstimate.ofText("{\"type\":\"object\"}"),
                TokenEstimate.ofTools(List.of(tool)));
        assertEquals(0, TokenEstimate.ofTools(List.of()));
    }
}
