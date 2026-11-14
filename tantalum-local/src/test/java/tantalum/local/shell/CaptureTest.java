package tantalum.local.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 命令输出太长时留头留尾、完整的写进文件（tool-scenarios-2 需求 6；DSH 笔记 2026-07-08-tool-output-spill-files、
 * {@code dsh-output-retention} 的头尾窗口）。出错信息常在最前面（编译错误、脚本第一行就失败），只留尾巴会丢掉它。
 */
class CaptureTest {

    @TempDir
    Path dir;

    private static void write(Capture capture, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i += 4096) {
            capture.write(bytes, i, Math.min(4096, bytes.length - i));
        }
    }

    private static String lines(int from, int to) {
        StringBuilder text = new StringBuilder();
        for (int i = from; i <= to; i++) {
            text.append("line ").append(i).append('\n');
        }
        return text.toString();
    }

    private Spill spill() throws Exception {
        return new Spill(dir.resolve("out"));
    }

    @Test
    void shortOutputIsKeptWholeAndNoFileIsWritten() throws Exception {
        Spill spill = spill();
        Capture capture = new Capture(spill);
        write(capture, "one\ntwo\n");
        Tail.Text kept = capture.text();
        assertEquals("one\ntwo", kept.text());
        assertFalse(kept.truncated());
        assertNull(kept.full());
        assertFalse(Files.exists(dir.resolve("out")) && Files.list(dir.resolve("out")).findAny().isPresent());
    }

    @Test
    void tooManyLinesKeepTheFirstAndTheLastLinesAndTheWholeGoesToAFile() throws Exception {
        Capture capture = new Capture(spill());
        write(capture, lines(1, 3000));
        Tail.Text kept = capture.text();
        assertTrue(kept.truncated());
        assertEquals(3000, kept.totalLines());
        assertEquals(Capture.HEAD_LINES, kept.headLines());
        assertEquals(Tail.MAX_LINES - Capture.HEAD_LINES, kept.shownLines() - kept.headLines());
        assertTrue(kept.text().startsWith("line 1\nline 2\n"), kept.text().substring(0, 40));
        assertTrue(kept.text().endsWith("line 3000"));
        assertTrue(kept.text().contains("\n[... 1000 lines omitted ...]\n"));
        assertFalse(kept.text().contains("\nline 1100\n"));
        assertNotNull(kept.full());
        assertEquals(lines(1, 3000), Files.readString(kept.full()));
    }

    @Test
    void anErrorOnTheFirstLineSurvivesALongOutput() throws Exception {
        Capture capture = new Capture(spill());
        write(capture, "ERROR E-4471: config missing\n" + "x".repeat(70) + "\n" + lines(1, 1500).repeat(2));
        Tail.Text kept = capture.text();
        assertTrue(kept.truncated());
        assertTrue(kept.text().startsWith("ERROR E-4471: config missing\n"));
        assertTrue(kept.text().getBytes(StandardCharsets.UTF_8).length <= Tail.MAX_BYTES + 100);
        assertTrue(Files.readString(kept.full()).startsWith("ERROR E-4471"));
    }

    @Test
    void tooManyBytesAlsoKeepHeadAndTail() throws Exception {
        Capture capture = new Capture(spill());
        String wide = "y".repeat(500);
        StringBuilder text = new StringBuilder("first\n");
        for (int i = 0; i < 400; i++) {
            text.append(wide).append(' ').append(i).append('\n');
        }
        text.append("last\n");
        write(capture, text.toString());
        Tail.Text kept = capture.text();
        assertTrue(kept.truncated());
        assertTrue(kept.text().startsWith("first\n"));
        assertTrue(kept.text().endsWith("last"));
        assertTrue(kept.text().getBytes(StandardCharsets.UTF_8).length <= Tail.MAX_BYTES + 100);
        assertEquals(text.toString(), Files.readString(kept.full()));
    }

    @Test
    void withoutASpillTheHeadAndTailAreStillKept() {
        Capture capture = new Capture(null);
        write(capture, lines(1, 3000));
        Tail.Text kept = capture.text();
        assertTrue(kept.text().startsWith("line 1\n"));
        assertTrue(kept.text().endsWith("line 3000"));
        assertNull(kept.full());
    }

    @Test
    void theFileStopsAtItsCapAndSaysSo() throws Exception {
        Capture capture = new Capture(spill(), 64 * 1024);
        write(capture, lines(1, 20000));
        Tail.Text kept = capture.text();
        String full = Files.readString(kept.full());
        assertTrue(full.length() < 70 * 1024, String.valueOf(full.length()));
        assertTrue(full.endsWith(Capture.FILE_CUT), full.substring(full.length() - 120));
        assertTrue(kept.fullCut());
        assertTrue(kept.text().endsWith("line 20000"));
    }

    @Test
    void closingTheSpillRemovesItsFiles() throws Exception {
        Spill spill = spill();
        Capture capture = new Capture(spill);
        write(capture, lines(1, 3000));
        Path file = capture.text().full();
        assertTrue(Files.exists(file));
        spill.close();
        assertFalse(Files.exists(file));
        assertFalse(Files.exists(spill.root()));
    }
}
