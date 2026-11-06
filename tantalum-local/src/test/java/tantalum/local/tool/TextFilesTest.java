package tantalum.local.tool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TextFilesTest {

    @TempDir
    Path temp;

    private Path file(String name, byte[] bytes) throws IOException {
        Path file = temp.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    private List<TextFiles.Line> lines(byte[] bytes, int maxChars) throws IOException {
        List<TextFiles.Line> lines = new ArrayList<>();
        try (TextFiles.LineReader reader = TextFiles.lines(file("lines.txt", bytes), maxChars)) {
            for (TextFiles.Line line = reader.next(); line != null; line = reader.next()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private List<String> texts(String content) throws IOException {
        return lines(content.getBytes(StandardCharsets.UTF_8), 2000).stream().map(TextFiles.Line::text).toList();
    }

    static Stream<Arguments> splits() {
        return Stream.of(
                Arguments.of("", List.of()),
                Arguments.of("\n", List.of("")),
                Arguments.of("a", List.of("a")),
                Arguments.of("a\n", List.of("a")),
                Arguments.of("a\nb", List.of("a", "b")),
                Arguments.of("a\r\nb\r\n", List.of("a", "b")),
                Arguments.of("a\rb\n", List.of("a\rb")),
                Arguments.of("a\r", List.of("a\r")),
                Arguments.of("﻿a\nb", List.of("a", "b")),
                Arguments.of("﻿", List.of()),
                Arguments.of("\n\n", List.of("", "")));
    }

    @ParameterizedTest
    @MethodSource("splits")
    void splitsOnLfAndDropsTheCrBeforeIt(String content, List<String> expected) throws IOException {
        assertEquals(expected, texts(content));
    }

    @Test
    void multiByteCharactersSurviveTheBufferBoundary() throws IOException {
        // 64K 个字符的缓冲区边界落在多字节字符中间
        String line = "x".repeat(64 * 1024 - 1) + "汉字😀";
        List<String> read = lines((line + "\n尾\n").getBytes(StandardCharsets.UTF_8), Integer.MAX_VALUE).stream()
                .map(TextFiles.Line::text).toList();
        assertEquals(List.of(line, "尾"), read);
    }

    @Test
    void longLinesAreCutAndMarked() throws IOException {
        List<TextFiles.Line> lines = lines(("a".repeat(5000) + "\nshort\n").getBytes(StandardCharsets.UTF_8), 2000);
        assertEquals("a".repeat(2000), lines.get(0).text());
        assertTrue(lines.get(0).truncated());
        assertEquals(new TextFiles.Line("short", false), lines.get(1));
    }

    @Test
    void aLineOfExactlyTheLimitIsNotCut() throws IOException {
        assertEquals(new TextFiles.Line("a".repeat(2000), false), lines("a".repeat(2000).getBytes(StandardCharsets.UTF_8), 2000).get(0));
    }

    @Test
    void cuttingNeverSplitsASurrogatePair() throws IOException {
        TextFiles.Line line = lines(("a".repeat(1999) + "😀b").getBytes(StandardCharsets.UTF_8), 2000).get(0);
        assertEquals("a".repeat(1999), line.text());
        assertTrue(line.truncated());
    }

    @Test
    void invalidUtf8IsReportedWhileReading() throws IOException {
        byte[] bytes = {'o', 'k', '\n', (byte) 0xC3, (byte) 0x28, '\n'};
        assertThrows(CharacterCodingException.class, () -> lines(bytes, 2000));
        assertThrows(CharacterCodingException.class, () -> TextFiles.read(file("bad.txt", bytes)));
    }

    @Test
    void aNulByteInTheFirst8KbMeansBinary() throws IOException {
        byte[] early = new byte[100];
        early[50] = 0;
        assertTrue(TextFiles.isBinary(file("early.bin", early)));

        byte[] late = "x".repeat(TextFiles.SNIFF_BYTES).getBytes(StandardCharsets.US_ASCII);
        byte[] withLateNul = new byte[late.length + 1];
        System.arraycopy(late, 0, withLateNul, 0, late.length);
        assertFalse(TextFiles.isBinary(file("late.txt", withLateNul)));
        assertFalse(TextFiles.isBinary(file("empty.txt", new byte[0])));
    }

    @Test
    void crlfWinsWhenItIsTheMajority() {
        assertTrue(TextFiles.isCrlf("a\r\nb\r\nc\n"));
        assertFalse(TextFiles.isCrlf("a\r\nb\nc\n"));
        assertFalse(TextFiles.isCrlf("no newline"));
        // 只看开头 4096 个字符
        assertFalse(TextFiles.isCrlf("a\n".repeat(2048) + "b\r\n".repeat(10)));
    }

    @Test
    void readAndEncodeKeepBomAndLineEndings() throws IOException {
        byte[] original = "﻿one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8);
        TextFiles.Text text = TextFiles.read(file("win.txt", original));
        assertEquals("one\ntwo\n", text.content());
        assertTrue(text.bom());
        assertTrue(text.crlf());
        assertArrayEquals(original, text.encode(text.content()));
        assertArrayEquals("﻿one\r\n2\r\n".getBytes(StandardCharsets.UTF_8), text.encode("one\n2\n"));

        TextFiles.Text plain = TextFiles.read(file("unix.txt", "a\nb".getBytes(StandardCharsets.UTF_8)));
        assertFalse(plain.bom());
        assertFalse(plain.crlf());
        assertArrayEquals("a\nc".getBytes(StandardCharsets.UTF_8), plain.encode("a\nc"));
    }

    @Test
    void atomicWriteCreatesAPrivateFileAndKeepsAnExistingMode() throws IOException {
        Path fresh = temp.resolve("fresh.txt");
        TextFiles.writeAtomically(fresh, "new".getBytes(StandardCharsets.UTF_8));
        assertEquals("new", Files.readString(fresh));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(fresh)));

        Path script = file("run.sh", "old".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        TextFiles.writeAtomically(script, "updated".getBytes(StandardCharsets.UTF_8));
        assertEquals("updated", Files.readString(script));
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(script)));
        assertEquals(List.of("fresh.txt", "run.sh"), names());
    }

    @Test
    void aFailedWriteLeavesNoTemporaryFile() throws IOException {
        Path directory = Files.createDirectory(temp.resolve("occupied"));
        Files.writeString(directory.resolve("inside"), "x");
        // 目标是非空目录，改名会失败
        assertThrows(IOException.class, () -> TextFiles.writeAtomically(directory, "x".getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of("occupied"), names());
    }

    @Test
    void parentsAreCreatedPrivately() throws IOException {
        Path file = temp.resolve("a/b/c.txt");
        TextFiles.createParents(file);
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(temp.resolve("a"))));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(temp.resolve("a/b"))));
        assertFalse(Files.exists(file));
    }

    private List<String> names() throws IOException {
        try (Stream<Path> children = Files.list(temp)) {
            return children.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
