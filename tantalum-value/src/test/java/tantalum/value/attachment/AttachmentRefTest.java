package tantalum.value.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AttachmentRefTest {

    private static final String HASH = "0123456789abcdef".repeat(4);
    private static final String ID = "sha256:" + HASH;

    @Test
    void idIsSha256WithLowercaseHex() {
        assertTrue(AttachmentRef.isId(ID));
        List<String> bad = List.of(
                HASH,                                   // 没有前缀
                "sha256:" + HASH.toUpperCase(),         // 大写
                "sha256:" + HASH.substring(1),          // 63 位
                "sha256:" + HASH + "0",                 // 65 位
                "sha256:" + HASH.substring(1) + "g",    // 非十六进制
                "sha256:../" + HASH.substring(3),       // 想拼出路径
                "SHA256:" + HASH);
        for (String id : bad) {
            assertFalse(AttachmentRef.isId(id), id);
            assertThrows(IllegalArgumentException.class, () -> new FileRef(id, "a.txt", "text/plain", 1, null), id);
        }
        assertThrows(NullPointerException.class, () -> new FileRef(null, "a.txt", "text/plain", 1, null));
    }

    @Test
    void readonlyPathIsVirtualAndBuiltFromHash() {
        FileRef ref = new FileRef(ID, "a.txt", "text/plain", 1, null);
        assertEquals(HASH, ref.hash());
        assertEquals("/attachments/sha256-" + HASH, ref.readonlyPath());
    }

    @Test
    void imageAcceptsOnlyFourTypesAndConsistentSizes() {
        assertThrows(IllegalArgumentException.class, () -> new ImageRef(ID, "a.svg", "image/svg+xml", 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ImageRef(ID, "a.png", "image/png", 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ImageRef(ID, " ", "image/png", 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageRef(ID, "a.png", "image/png", 1, 100, 50, 200, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageRef(ID, "a.png", "image/png", 1, 100, 50, 80, 40));
        new ImageRef(ID, "a.png", "image/png", 1, 2048, 1024, 4096, 2048);
    }

    @Test
    void pdfIsMarkedByPageCount() {
        assertTrue(new FileRef(ID, "a.pdf", "application/pdf", 10, 3).pdf());
        assertFalse(new FileRef(ID, "a.pdf", "application/pdf", 10, null).pdf());
        assertThrows(IllegalArgumentException.class, () -> new FileRef(ID, "a.pdf", "application/pdf", 10, 0));
    }

    /** 期望值从设计第 4 节逐字抄来，只把占位换成具体值。 */
    @Test
    void modelFacingTextMatchesDesign() {
        ImageRef image = new ImageRef(ID, "cat.png", "image/png", 1234, 800, 600);
        FileRef file = new FileRef(ID, "notes.txt", "text/plain", 1234, null);
        assertEquals("Image \"cat.png\" (sha256:01234567, 800x600px). Read-only copy: \"/attachments/sha256-" + HASH
                + "\".", AttachmentText.image(image));
        assertEquals("[File \"notes.txt\" (1234 bytes, sha256:01234567): verbatim read-only copy saved at \"/attachments/sha256-"
                + HASH + "\". Read that path with read_file when its contents are needed.]", AttachmentText.file(file));
        assertEquals("[image omitted to fit request image limits; \"cat.png\" (sha256:01234567). Read-only copy: \"/attachments/sha256-"
                + HASH + "\".]", AttachmentText.omitted(image));
        assertEquals("[image omitted because this model accepts text only; sha256:01234567]", AttachmentText.textOnly(image));
    }

    @Test
    void quotesInNamesCannotBreakOutOfTheQuotedName() {
        ImageRef image = new ImageRef(ID, "a\" (sha256:ffffffff", "image/png", 1, 1, 1);
        assertTrue(AttachmentText.image(image).startsWith("Image \"a\\\" (sha256:ffffffff\" (sha256:01234567,"));
    }
}
