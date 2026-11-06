package tantalum.local.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.AllowedDirs;
import tantalum.value.json.Json;

/** core-tools 需求 1 的验收标准，逐条对应。 */
class ReadFileWindowTest {

    @TempDir
    Path root;

    private final ReadFile read = new ReadFile();
    private final Observations observations = new Observations();
    private LocalContext context;

    @BeforeEach
    void setUp() throws Exception {
        context = new LocalContext("s", new AllowedDirs(List.of(root)), observations);
    }

    private static Json args(Object... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put((String) pairs[i], pairs[i + 1] instanceof Integer n ? new Json.Num(java.math.BigDecimal.valueOf(n))
                    : new Json.Str((String) pairs[i + 1]));
        }
        return new Json.Obj(fields);
    }

    private LocalResult read(Object... pairs) {
        return read.run(context, args(pairs));
    }

    private void lines(String name, int count) throws Exception {
        Files.writeString(root.resolve(name), numbered(1, count) + "\n");
    }

    private static String numbered(int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));
    }

    // 1. 短文件返回全文，和以前一样
    @Test
    void aShortFileComesBackWhole() throws Exception {
        Files.writeString(root.resolve("a.txt"), "one\ntwo\n");
        LocalResult output = read("path", "a.txt");
        assertFalse(output.error());
        assertEquals("one\ntwo", output.text());
    }

    // 2. 超过 2000 行只给开头 2000 行，结尾写明续读
    @Test
    void longFilesStopAt2000LinesWithAContinuation() throws Exception {
        lines("long.txt", 5000);
        LocalResult output = read("path", "long.txt");
        assertEquals(numbered(1, 2000) + "\n\n[Showing lines 1-2000 of 5000. Use offset=2001 to continue.]", output.text());
    }

    // 2. 超过 50 KB 只给不超过 50 KB 的整行
    @Test
    void wideFilesStopAt50Kb() throws Exception {
        String line = "x".repeat(999);
        Files.writeString(root.resolve("wide.txt"), (line + "\n").repeat(100));
        LocalResult output = read("path", "wide.txt");
        assertTrue(output.text().startsWith((line + "\n").repeat(51) + "\n[Showing lines 1-51 of 100"), output.text().substring(0, 80));
        assertTrue(output.text().endsWith("Use offset=52 to continue.]"));
        String content = output.text().substring(0, output.text().indexOf("\n\n["));
        assertTrue(content.getBytes(StandardCharsets.UTF_8).length + 1 <= ReadFile.MAX_WINDOW_BYTES);
    }

    // 3. offset 和 limit；没到末尾写明下一次的 offset；到末尾不写
    @Test
    void offsetAndLimitPickAWindow() throws Exception {
        lines("long.txt", 5000);
        assertEquals(numbered(10, 12) + "\n\n[Showing lines 10-12 of 5000. Use offset=13 to continue.]",
                read("path", "long.txt", "offset", 10, "limit", 3).text());
        assertEquals(numbered(4999, 5000), read("path", "long.txt", "offset", 4999, "limit", 10).text());
        assertEquals(numbered(4001, 5000), read("path", "long.txt", "offset", 4001).text());
    }

    // 3. limit 最大 2000
    @Test
    void limitIsCappedAt2000() throws Exception {
        lines("long.txt", 5000);
        assertTrue(read("path", "long.txt", "limit", 9999).text().endsWith("[Showing lines 1-2000 of 5000. Use offset=2001 to continue.]"));
    }

    @Test
    void numbersWrittenAsStringsAreAccepted() throws Exception {
        lines("long.txt", 10);
        assertEquals(numbered(2, 3) + "\n\n[Showing lines 2-3 of 10. Use offset=4 to continue.]",
                read("path", "long.txt", "offset", "2", "limit", "2").text());
    }

    @Test
    void badNumbersAreReported() throws Exception {
        lines("a.txt", 3);
        assertEquals("offset must be 1 or more", read("path", "a.txt", "offset", 0).text());
        assertEquals("limit must be 1 or more", read("path", "a.txt", "limit", -1).text());
        assertEquals("offset must be an integer", read("path", "a.txt", "offset", "two").text());
    }

    // 4. offset 超出时报错并写明共有多少行
    @Test
    void anOffsetPastTheEndIsAnError() throws Exception {
        lines("a.txt", 3);
        LocalResult output = read("path", "a.txt", "offset", 4);
        assertTrue(output.error());
        assertEquals("offset 4 is past the end of a.txt (3 lines)", output.text());
    }

    @Test
    void anEmptyFileReadsAsEmpty() throws Exception {
        Files.writeString(root.resolve("empty.txt"), "");
        LocalResult output = read("path", "empty.txt");
        assertFalse(output.error());
        assertEquals("", output.text());
        assertTrue(read("path", "empty.txt", "offset", 2).error());
    }

    @Test
    void theLastLineNeedsNoNewline() throws Exception {
        Files.writeString(root.resolve("a.txt"), "one\ntwo");
        assertEquals("one\ntwo", read("path", "a.txt").text());
    }

    @Test
    void exactly2000LinesNeedNoContinuation() throws Exception {
        lines("a.txt", 2000);
        assertEquals(numbered(1, 2000), read("path", "a.txt").text());
    }

    // 5. 长行截断并注明，不影响其余的行
    @Test
    void longLinesAreCutAndMarked() throws Exception {
        Files.writeString(root.resolve("min.js"), "a".repeat(10_000) + "\nnext\n");
        assertEquals("a".repeat(2000) + "... [line truncated to 2000 characters]\nnext", read("path", "min.js").text());
    }

    // C3：去掉行尾的 \r，不加行号；BOM 去掉
    @Test
    void crlfAndBomAreNotShown() throws Exception {
        Files.write(root.resolve("win.txt"), "﻿one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("one\ntwo", read("path", "win.txt").text());
    }

    // 6. 超过 64 MB、二进制、非 UTF-8 报错说明原因
    @Test
    void filesOver64MbAreRefusedWithAHint() throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(root.resolve("huge.log").toFile(), "rw")) {
            file.setLength(ReadFile.MAX_FILE_BYTES + 1);
        }
        LocalResult output = read("path", "huge.log");
        assertTrue(output.error());
        assertTrue(output.text().contains("larger than 64 MB") && output.text().contains("grep"), output.text());
    }

    @Test
    void binaryAndInvalidUtf8AreRefused() throws Exception {
        Files.write(root.resolve("a.bin"), new byte[] {'a', 0, 'b'});
        assertEquals("Binary file, not shown: a.bin", read("path", "a.bin").text());
        Files.write(root.resolve("latin1.txt"), new byte[] {'c', 'a', 'f', (byte) 0xE9, '\n'});
        assertEquals("Not a UTF-8 text file: latin1.txt", read("path", "latin1.txt").text());
    }

    // C5：读任何一个窗口都算读过
    @Test
    void readingAWindowCountsAsHavingReadTheFile() throws Exception {
        lines("long.txt", 5000);
        Path file = root.resolve("long.txt").toRealPath();
        assertEquals(Observations.Check.NOT_READ, observations.check(context.session(), file));
        read.run(context, args("path", "long.txt", "offset", 100, "limit", 5));
        assertEquals(Observations.Check.OK, observations.check(context.session(), file));
        read.run(context, args("path", "missing.txt"));
        Files.write(root.resolve("a.bin"), new byte[] {0});
        read.run(context, args("path", "a.bin"));
        assertEquals(Observations.Check.NOT_READ, observations.check(context.session(), root.resolve("a.bin").toRealPath()));
    }

    // 8. 工具说明告诉模型分段读
    @Test
    void theDescriptionExplainsOffsetAndLimit() {
        assertTrue(read.description().contains("offset and limit"));
        assertTrue(read.schema().toString().contains("integer"));
    }
}
