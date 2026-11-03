package tantalum.shell.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 设计第 2 节的文件名清洗规则，一行一条。 */
class FileNamesTest {

    private static void cleans(String raw, String expected) {
        assertEquals(expected, FileNames.clean(raw, false), () -> "clean(" + raw + ")");
    }

    @Test
    void keepsOrdinaryNames() {
        cleans("report.pdf", "report.pdf");
        cleans("季度 报告 (终版).docx", "季度 报告 (终版).docx");
        cleans("photo 😀.jpg", "photo 😀.jpg");
    }

    @Test
    void keepsOnlyTheLastPathSegment() {
        cleans("/etc/passwd", "passwd");
        cleans("../../secret.txt", "secret.txt");
        cleans("C:\\Users\\yi\\Desktop\\a.txt", "a.txt");
        cleans("dir/sub\\b.txt", "b.txt");
    }

    @Test
    void dropsControlAndBidiCharacters() {
        cleans("a\u0000b\nc\td\u007f.txt", "abcd.txt");
        cleans("invoice\u202Etxt.exe", "invoicetxt.exe");
    }

    @Test
    void replacesCharactersWindowsRejects() {
        cleans("a<b>c:d\"e|f?g*h.txt", "a_b_c_d_e_f_g_h.txt");
    }

    @Test
    void trimsWhitespaceAndDotsAtBothEnds() {
        cleans("  .hidden.txt. ", "hidden.txt");
        cleans("name.txt...", "name.txt");
    }

    @Test
    void truncatesToUtf8BytesWithoutSplittingCharacters() {
        cleans("a".repeat(300), "a".repeat(255));
        String chinese = FileNames.clean("文".repeat(100), false); // 每个 3 字节，255 / 3 = 85
        assertEquals("文".repeat(85), chinese);
        String emoji = FileNames.clean("a" + "😀".repeat(70), false); // 1 + 4 × 63 = 253，再加一个就超
        assertEquals("a" + "😀".repeat(63), emoji);
        assertTrue(emoji.getBytes(StandardCharsets.UTF_8).length <= 255);
        cleans("a".repeat(254) + " .b", "a".repeat(254)); // 截完结尾是空格，再去一次
    }

    @Test
    void emptyAfterCleaningFallsBackByKind() {
        cleans(null, "file");
        cleans("", "file");
        cleans("..", "file");
        cleans("dir/", "file");
        cleans("\u0001\u0002", "file");
        assertEquals("image", FileNames.clean(" . ", true));
    }
}
