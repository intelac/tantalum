package tantalum.kernel.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.SessionState;

class TokenEstimateTest {

    @Test
    void asciiIsBytesOverThreeRoundedUp() {
        assertEquals(0, TokenEstimate.LEGACY.ofText(""));
        assertEquals(0, TokenEstimate.LEGACY.ofText(null));
        assertEquals(1, TokenEstimate.LEGACY.ofText("a"));
        assertEquals(1, TokenEstimate.LEGACY.ofText("abc"));
        assertEquals(2, TokenEstimate.LEGACY.ofText("abcd"));
    }

    @Test
    void theCoefficientDividesTheBytes() {
        TokenEstimate claude = new TokenEstimate(1.5);
        assertEquals(2, claude.ofText("abc"));
        assertEquals(3, claude.ofText("abcd"), "4 / 1.5 rounds up");
        assertEquals(6, claude.ofText("你好你"), "nine bytes");
        assertEquals(5, new TokenEstimate(2.0).ofText("你好你"));
        TokenEstimate json = new TokenEstimate(1.5);
        ToolSpec tool = new ToolSpec("t", "", new Json.Obj(Map.of("a", new Json.Str("b"))));
        // 模式按 10 字节算（键加 4 字节的引号、冒号和逗号），10 / 1.5 向上取整是 7
        assertEquals(json.ofText("t") + 7, json.ofTools(List.of(tool)), "JSON uses the coefficient too");
    }

    @Test
    void settingsWithoutACoefficientEstimateTheOldWay() {
        assertEquals(TokenEstimate.LEGACY, TokenEstimate.of(new CompactionSettings(1_000, 100, 10, 10, 10, 10)));
        assertEquals(new TokenEstimate(1.5), TokenEstimate.of(new CompactionSettings(1_000, 100, 10, 10, 10, 10, 500, 1.5)));
        assertThrows(IllegalArgumentException.class, () -> new TokenEstimate(0));
    }

    @Test
    void imagesAndPdfPagesIgnoreTheCoefficient() {
        TokenEstimate claude = new TokenEstimate(1.5);
        long pixels = (2048L * 1536 + 749) / 750;
        assertEquals(pixels + claude.ofText(AttachmentText.image(IMAGE)), claude.ofImage(IMAGE));
    }

    @Test
    void chineseCountsUtf8BytesNotCharacters() {
        assertEquals(3, TokenEstimate.LEGACY.ofText("你好你"), "three characters are nine bytes");
        assertTrue(TokenEstimate.LEGACY.ofText("你好") > TokenEstimate.LEGACY.ofText("hi"));
    }

    @Test
    void messageCountsEveryKindOfBlock() {
        Message message = new Message(Role.ASSISTANT, List.of(
                new TextBlock("abc"),
                new ReasoningBlock("claude", new Json.Obj(Map.of("thinking", new Json.Str("deep")))),
                new ToolCallBlock("t1", "grep", "{\"q\":\"x\"}", new Json.Obj(Map.of()))), false);
        long text = 1;
        long reasoning = TokenEstimate.LEGACY.ofText("{\"thinking\":\"deep\"}");
        long call = TokenEstimate.LEGACY.ofText("grep") + TokenEstimate.LEGACY.ofText("{\"q\":\"x\"}");
        assertEquals(text + reasoning + call, TokenEstimate.LEGACY.ofMessage(message));
        assertEquals(TokenEstimate.LEGACY.ofText("result"), TokenEstimate.LEGACY.ofMessage(
                new Message(Role.USER, List.of(new ToolResultBlock("t1", "result", false, null)), false)));
    }

    @Test
    void listsAddUp() {
        List<Message> messages = List.of(Message.user("abc"), Message.user("abcd"));
        assertEquals(3, TokenEstimate.LEGACY.ofMessages(messages));
        ToolSpec tool = new ToolSpec("grep", "search", new Json.Obj(Map.of("type", new Json.Str("object"))));
        assertEquals(TokenEstimate.LEGACY.ofText("grep") + TokenEstimate.LEGACY.ofText("search") + TokenEstimate.LEGACY.ofText("{\"type\":\"object\"}"),
                TokenEstimate.LEGACY.ofTools(List.of(tool)));
        assertEquals(0, TokenEstimate.LEGACY.ofTools(List.of()));
    }

    // ---- 附件（A11）----

    private static final ImageRef IMAGE = new ImageRef("sha256:" + "a".repeat(64), "cat.png", "image/png", 99, 2048, 1536);

    @Test
    void imagesCountTheirPixelsPlusTheHandleText() {
        long pixels = (2048L * 1536 + 749) / 750; // ceil(3145728 / 750) = 4195
        assertEquals(4195, pixels);
        assertEquals(pixels + TokenEstimate.LEGACY.ofText(AttachmentText.image(IMAGE)), TokenEstimate.LEGACY.ofImage(IMAGE));
        assertEquals(TokenEstimate.LEGACY.ofImage(IMAGE), TokenEstimate.LEGACY.ofMessage(new Message(Role.USER, List.of(new AttachmentBlock(IMAGE)), false)));
        assertEquals(TokenEstimate.LEGACY.ofText("x") + TokenEstimate.LEGACY.ofImage(IMAGE), TokenEstimate.LEGACY.ofMessage(new Message(Role.USER,
                List.of(new ToolResultBlock("c", "x", false, null, List.of(IMAGE))), false)));
    }

    @Test
    void filesCountTheirHandleTextAndPdfsThreeThousandPerPage() {
        FileRef text = new FileRef("sha256:" + "b".repeat(64), "a.txt", "text/plain", 5_000_000, null);
        FileRef pdf = new FileRef("sha256:" + "b".repeat(64), "a.pdf", "application/pdf", 5_000_000, 4);
        assertEquals(TokenEstimate.LEGACY.ofText(AttachmentText.file(text)), TokenEstimate.LEGACY.ofMessage(user(text)));
        assertEquals(TokenEstimate.LEGACY.ofText(AttachmentText.file(pdf)) + 4 * 3000, TokenEstimate.LEGACY.ofMessage(user(pdf)));
    }

    /** 省略过的图片按省略说明算，和实际发送的一致。 */
    @Test
    void sessionSizeCountsOmittedImagesAsTheirText() {
        SessionState session = SessionState.start(new SessionId("s"), new Principal("u")).append(user(IMAGE));
        long full = Compactor.size(session, "", List.of());
        long omitted = Compactor.size(session.withOmittedImages(List.of(IMAGE.id())), "", List.of());
        assertEquals(TokenEstimate.LEGACY.ofImage(IMAGE), full);
        assertEquals(TokenEstimate.LEGACY.ofText(AttachmentText.omitted(IMAGE)), omitted);
    }

    private static Message user(AttachmentRef ref) {
        return new Message(Role.USER, List.of(new AttachmentBlock(ref)), false);
    }
}
