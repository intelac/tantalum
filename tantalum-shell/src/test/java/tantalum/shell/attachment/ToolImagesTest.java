package tantalum.shell.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.attachment.ToolImages.Image;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.attachment.AttachmentText;
import tantalum.value.attachment.ImageRef;
import tantalum.value.id.Principal;

/** W8、需求 3.1：工具结果里的图片照附件规则存进本人附件，结果里是引用；存不下时写明原因。 */
class ToolImagesTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");

    @TempDir
    Path temp;

    private static byte[] sample(String name) throws IOException {
        try (InputStream in = ToolImagesTest.class.getResourceAsStream("/attachments/" + name)) {
            return in.readAllBytes();
        }
    }

    private static AttachmentStore store(Path dir, long quota) throws IOException {
        AttachmentLimits limits = new AttachmentLimits(20, 1L << 30, 50L << 20, 64_000_000, 65_535, 20, 50L << 20, quota, 2);
        return new AttachmentStore(dir, limits, new ImageNormalizer(limits));
    }

    @Test
    void pngIsStoredAsTheUsersAttachmentAndTheTextGetsTheImageLine() throws Exception {
        AttachmentStore store = store(temp, 1L << 30);
        ToolOutput output = new ToolImages(store).output(ALICE, "Screenshot of example.com", false,
                List.of(new Image("image/png", sample("clean.png"))), "screenshot-example.com.png");
        assertFalse(output.error(), output.text());
        ImageRef image = output.images().getFirst();
        assertEquals("screenshot-example.com.png", image.name());
        assertEquals("image/png", image.mediaType());
        assertEquals("Screenshot of example.com\n" + AttachmentText.image(image), output.text());
        assertTrue(store.find(ALICE, image.id()).isPresent());
        assertTrue(store.find(BOB, image.id()).isEmpty(), "only the caller's attachments");
    }

    /** 本机工作区读图时文字为空：结果只有图片那一行（LocalTool 现在的行为）。 */
    @Test
    void emptyTextGivesOnlyTheImageLines() throws Exception {
        ToolOutput output = new ToolImages(store(temp, 1L << 30)).output(ALICE, "", false,
                List.of(new Image("image/jpeg", sample("clean.jpg")), new Image("image/png", sample("clean.png"))), "photo");
        assertEquals(2, output.images().size());
        assertEquals(AttachmentText.image(output.images().get(0)) + "\n" + AttachmentText.image(output.images().get(1)), output.text());
    }

    @Test
    void aFullQuotaIsAnErrorThatSaysWhy() throws Exception {
        ToolOutput output = new ToolImages(store(temp, 10)).output(ALICE, "x", false,
                List.of(new Image("image/png", sample("clean.png"))), "shot.png");
        assertTrue(output.error());
        assertTrue(output.images().isEmpty());
        assertTrue(output.text().startsWith("Cannot store image shot.png: "), output.text());
        assertTrue(output.text().length() > "Cannot store image shot.png: ".length(), "the attachment reason is kept");
    }

    @Test
    void bytesThatAreNotAnImageAreAnError() throws Exception {
        ToolOutput output = new ToolImages(store(temp, 1L << 30)).output(ALICE, "", false,
                List.of(new Image("image/png", "not a picture".getBytes(StandardCharsets.UTF_8))), "fake");
        assertTrue(output.error());
        assertEquals("Not an image: fake", output.text());
    }
}
