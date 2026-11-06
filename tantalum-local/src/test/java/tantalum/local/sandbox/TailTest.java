package tantalum.local.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 命令输出只留最后 2000 行或 50 KB（L14），先到哪个算哪个；不留半行，不留半个字符。 */
class TailTest {

    private static void write(Tail tail, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        // 分小块写，像从管道里读出来的一样
        for (int i = 0; i < bytes.length; i += 7) {
            tail.write(bytes, i, Math.min(7, bytes.length - i));
        }
    }

    @Test
    void shortOutputIsKeptWhole() {
        Tail tail = new Tail();
        write(tail, "one\ntwo\n");
        assertEquals(new Tail.Text("one\ntwo", false, 2, 2), tail.text());
        assertEquals(new Tail.Text("", false, 0, 0), new Tail().text());
    }

    @Test
    void theLineLimitKeepsTheLastLines() {
        Tail tail = new Tail();
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 5000; i++) {
            text.append(i).append('\n');
        }
        write(tail, text.toString());
        Tail.Text kept = tail.text();
        assertTrue(kept.truncated());
        assertEquals(5000, kept.totalLines());
        assertEquals(Tail.MAX_LINES, kept.shownLines());
        assertTrue(kept.text().startsWith("3001\n") && kept.text().endsWith("\n5000"), kept.text().substring(0, 20));
    }

    /** 字节上限先到：从被截的那一行的下一行开始；中间的多字节字符不被切坏。 */
    @Test
    void theByteLimitStartsAtAWholeLine() {
        Tail tail = new Tail(2000, 100);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            text.append("行").append(i).append("-abcdefgh\n");
        }
        write(tail, text.toString());
        Tail.Text kept = tail.text();
        assertTrue(kept.truncated());
        assertEquals(50, kept.totalLines());
        assertTrue(kept.text().getBytes(StandardCharsets.UTF_8).length <= 100);
        assertTrue(kept.text().startsWith("行"), kept.text());
        assertTrue(kept.text().endsWith("行49-abcdefgh"), kept.text());
        assertFalse(kept.text().contains("�"));
    }

    /** 一整行比上限还长：留下它的末尾，跳过半个字符。 */
    @Test
    void oneHugeLineKeepsItsEnd() {
        Tail tail = new Tail(2000, 10);
        write(tail, "开头" + "字".repeat(20) + "END");
        Tail.Text kept = tail.text();
        assertTrue(kept.truncated());
        assertTrue(kept.text().endsWith("END"));
        assertFalse(kept.text().contains("�"), kept.text());
        assertEquals(1, kept.totalLines());
    }

    /** 一次写入比上限还大也只占上限的两倍。 */
    @Test
    void aLargeWriteKeepsOnlyItsEnd() {
        Tail tail = new Tail(2000, 16);
        byte[] big = ("x".repeat(100) + "\nlast line\n").getBytes(StandardCharsets.UTF_8);
        tail.write(big, 0, big.length);
        assertEquals(new Tail.Text("last line", true, 2, 1), tail.text());
    }
}
