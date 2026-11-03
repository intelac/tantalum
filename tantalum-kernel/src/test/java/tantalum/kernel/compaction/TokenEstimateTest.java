package tantalum.kernel.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.AttachmentText;
import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.AttachmentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.SessionState;

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

    // ---- 附件（A11）----

    private static final ImageRef IMAGE = new ImageRef("sha256:" + "a".repeat(64), "cat.png", "image/png", 99, 2048, 1536);

    @Test
    void imagesCountTheirPixelsPlusTheHandleText() {
        long pixels = (2048L * 1536 + 749) / 750; // ceil(3145728 / 750) = 4195
        assertEquals(4195, pixels);
        assertEquals(pixels + TokenEstimate.ofText(AttachmentText.image(IMAGE)), TokenEstimate.ofImage(IMAGE));
        assertEquals(TokenEstimate.ofImage(IMAGE), TokenEstimate.ofMessage(new Message(Role.USER, List.of(new AttachmentBlock(IMAGE)), false)));
        assertEquals(TokenEstimate.ofText("x") + TokenEstimate.ofImage(IMAGE), TokenEstimate.ofMessage(new Message(Role.USER,
                List.of(new ToolResultBlock("c", "x", false, null, List.of(IMAGE))), false)));
    }

    @Test
    void filesCountTheirHandleTextAndPdfsThreeThousandPerPage() {
        FileRef text = new FileRef("sha256:" + "b".repeat(64), "a.txt", "text/plain", 5_000_000, null);
        FileRef pdf = new FileRef("sha256:" + "b".repeat(64), "a.pdf", "application/pdf", 5_000_000, 4);
        assertEquals(TokenEstimate.ofText(AttachmentText.file(text)), TokenEstimate.ofMessage(user(text)));
        assertEquals(TokenEstimate.ofText(AttachmentText.file(pdf)) + 4 * 3000, TokenEstimate.ofMessage(user(pdf)));
    }

    /** 省略过的图片按省略说明算，和实际发送的一致。 */
    @Test
    void sessionSizeCountsOmittedImagesAsTheirText() {
        SessionState session = SessionState.start(new SessionId("s"), new Principal("u")).append(user(IMAGE));
        long full = Compactor.size(session, "", List.of());
        long omitted = Compactor.size(session.withOmittedImages(List.of(IMAGE.id())), "", List.of());
        assertEquals(TokenEstimate.ofImage(IMAGE), full);
        assertEquals(TokenEstimate.ofText(AttachmentText.omitted(IMAGE)), omitted);
    }

    private static Message user(AttachmentRef ref) {
        return new Message(Role.USER, List.of(new AttachmentBlock(ref)), false);
    }
}
