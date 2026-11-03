package tantalum.kernel.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.attachment.AttachmentText;
import tantalum.value.attachment.ImageRef;
import tantalum.value.model.AttachmentBlock;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolResultBlock;

/** 任务 14：从新往旧累计 base64 大小，超过 20 MiB 后更早的全部省略；省略过的一直省略（A10）。 */
class ImageBudgetTest {

    /** base64 后正好 5 MiB：四张正好 20 MiB。 */
    private static final long FIVE_MIB_BASE64 = 3L * 1024 * 1024 * 5 / 4;

    private static ImageRef image(char tag, long bytes) {
        return new ImageRef("sha256:" + String.valueOf(tag).repeat(64), tag + ".png", "image/png", bytes, 10, 10);
    }

    private static Message user(ImageRef... images) {
        List<ContentBlock> content = new ArrayList<>(List.of(new TextBlock("look")));
        for (ImageRef image : images) {
            content.add(new AttachmentBlock(image));
        }
        return new Message(Role.USER, content, false);
    }

    @Test
    void base64SizeIsFourThirdsRoundedUp() {
        assertEquals(4, ImageBudget.base64(1));
        assertEquals(4, ImageBudget.base64(3));
        assertEquals(8, ImageBudget.base64(4));
        assertEquals(5L * 1024 * 1024, ImageBudget.base64(FIVE_MIB_BASE64));
    }

    @Test
    void exactlyTheBudgetIsKeptAndOneByteMoreOmitsTheOldest() {
        ImageRef a = image('a', FIVE_MIB_BASE64);
        ImageRef b = image('b', FIVE_MIB_BASE64);
        ImageRef c = image('c', FIVE_MIB_BASE64);
        ImageRef d = image('d', FIVE_MIB_BASE64);
        List<Message> four = List.of(user(a, b), user(c, d));
        ImageBudget.Applied fits = ImageBudget.apply(four, List.of());
        assertSame(four, fits.messages());
        assertEquals(List.of(), fits.omitted());

        ImageRef bigger = image('d', FIVE_MIB_BASE64 + 1);
        ImageBudget.Applied over = ImageBudget.apply(List.of(user(a, b), user(c, bigger)), List.of());
        assertEquals(List.of(a.id()), over.omitted());
        assertEquals(List.of(a.id()), over.newlyOmitted());
        assertEquals(new TextBlock(AttachmentText.omitted(a)), over.messages().getFirst().content().get(1));
        assertEquals(new AttachmentBlock(b), over.messages().getFirst().content().get(2));
    }

    /** 一旦超出，更早的图片哪怕很小也省略：只留“最近的一段”，不挑着塞。 */
    @Test
    void everythingOlderThanTheFirstOverflowIsOmitted() {
        ImageRef tiny = image('1', 10);
        ImageRef big = image('b', FIVE_MIB_BASE64 * 5);
        ImageRef recent = image('2', 10);
        ImageBudget.Applied applied = ImageBudget.apply(List.of(user(tiny), user(big), user(recent)), List.of());
        assertEquals(List.of(tiny.id(), big.id()), applied.omitted());
    }

    @Test
    void omittedImagesStayOmittedEvenWhenTheyWouldFitNow() {
        ImageRef old = image('3', 10);
        ImageRef recent = image('2', 10);
        ImageBudget.Applied applied = ImageBudget.apply(List.of(user(old), user(recent)), List.of(old.id()));
        assertEquals(List.of(old.id()), applied.omitted());
        assertEquals(List.of(), applied.newlyOmitted());
        assertEquals(new TextBlock(AttachmentText.omitted(old)), applied.messages().getFirst().content().get(1));
    }

    @Test
    void toolResultImagesAreOmittedIntoTheResultText() {
        ImageRef shot = image('4', 10);
        ImageRef kept = image('5', 10);
        Message result = new Message(Role.USER, List.of(new ToolResultBlock("c1", "read 2 images", false, null, List.of(shot, kept))), false);
        ToolResultBlock omitted = (ToolResultBlock) ImageBudget.apply(List.of(result), List.of(shot.id())).messages().getFirst()
                .content().getFirst();
        assertEquals("read 2 images\n" + AttachmentText.omitted(shot), omitted.text());
        assertEquals(List.of(kept), omitted.images());
    }
}
