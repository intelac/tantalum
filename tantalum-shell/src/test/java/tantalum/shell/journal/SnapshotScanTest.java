package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 倒着找最后一份快照（审查：原来每行重读一块 64 KiB）：一块读一次；快照行的开头被块的边界切开、在文件第一行之后、没有快照、
 * 尾部残缺，都找得对。
 */
class SnapshotScanTest {

    private static final int CHUNK = 64 * 1024;
    private static final String SNAPSHOT = new String(SnapshotCodec.PREFIX, StandardCharsets.UTF_8) + "7,\"state\":{}}\n";

    @TempDir
    Path temp;

    private Path file(String text) throws Exception {
        Path file = temp.resolve("f" + System.nanoTime() + ".jsonl");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private static String lines(int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            out.append("{\"seq\":").append(i).append(",\"x\":\"").append("y".repeat(80)).append("\"}\n");
        }
        return out.toString();
    }

    @Test
    void theLastSnapshotIsFoundWhereverTheChunksFall() throws Exception {
        String header = "{\"format\":4}\n";
        assertEquals(-1, SessionFileReader.lastSnapshot(file(header + lines(3000))), "no snapshot");
        for (int shift = -SnapshotCodec.PREFIX.length - 2; shift <= SnapshotCodec.PREFIX.length + 2; shift++) {
            String before = header + lines(2000);
            String after = lines(200);
            // 快照行的开头落在离文件尾一块整的地方附近：开头被块的边界切开
            int pad = Math.max(0, CHUNK - after.length() - SNAPSHOT.length() + shift);
            String filler = "{\"pad\":\"" + "z".repeat(Math.max(0, pad - 11)) + "\"}\n";
            String text = before + SNAPSHOT + filler + after;
            assertEquals(before.getBytes(StandardCharsets.UTF_8).length, SessionFileReader.lastSnapshot(file(text)), "shift " + shift);
        }
        String text = header + SNAPSHOT + lines(10) + SNAPSHOT + lines(10) + "{\"seq\":99,\"brok";
        assertEquals((header + SNAPSHOT + lines(10)).length(), SessionFileReader.lastSnapshot(file(text)), "the last one; the broken tail is not a line");
    }
}
