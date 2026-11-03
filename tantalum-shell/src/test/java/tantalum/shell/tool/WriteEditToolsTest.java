package tantalum.shell.tool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.Workspace;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/** core-tools 需求 2、3 的验收标准，逐条对应。 */
class WriteEditToolsTest {

    @TempDir
    Path temp;

    private Path root;
    private final Observations observations = new Observations();
    private final ReadFileTool read = new ReadFileTool(null, observations);
    private final WriteFileTool write = new WriteFileTool(observations);
    private final EditFileTool edit = new EditFileTool(observations);
    private ToolContext context;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectory(temp.resolve("root"));
        context = new ToolContext(new Principal("alice"), new SessionId("one"), new Workspace(root));
    }

    private static Json args(Object... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put((String) pairs[i], pairs[i + 1] instanceof Boolean flag ? new Json.Bool(flag) : new Json.Str((String) pairs[i + 1]));
        }
        return new Json.Obj(fields);
    }

    private ToolOutput read(String path) {
        return read.run(context, args("path", path));
    }

    private ToolOutput write(String path, String content) {
        return write.run(context, args("path", path, "content", content));
    }

    private ToolOutput edit(String path, String from, String to, Object... more) {
        Object[] pairs = new Object[6 + more.length];
        System.arraycopy(new Object[] {"path", path, "old_string", from, "new_string", to}, 0, pairs, 0, 6);
        System.arraycopy(more, 0, pairs, 6, more.length);
        return edit.run(context, args(pairs));
    }

    private String mode(String path) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve(path)));
    }

    // ---- write_file ----

    // 2.1 新文件：建上级目录，返回“已创建”、路径和字节数
    @Test
    void writingANewFileCreatesItsDirectories() throws Exception {
        ToolOutput output = write("src/main/App.java", "class App {}\n");
        assertEquals("Created src/main/App.java (13 bytes).", output.text());
        assertEquals("class App {}\n", Files.readString(root.resolve("src/main/App.java")));
        assertEquals("rw-------", mode("src/main/App.java"));
        assertEquals("rwx------", mode("src"));
        assertEquals("rwx------", mode("src/main"));
    }

    // 2.2 读过、没被改过：整个替换，“已更新”，保留权限
    @Test
    void overwritingAFileReadInThisSessionKeepsItsMode() throws Exception {
        Files.writeString(root.resolve("run.sh"), "echo old\n");
        Files.setPosixFilePermissions(root.resolve("run.sh"), PosixFilePermissions.fromString("rwxr-x---"));
        read("run.sh");
        assertEquals("Updated run.sh (9 bytes).", write("run.sh", "echo new\n").text());
        assertEquals("echo new\n", Files.readString(root.resolve("run.sh")));
        assertEquals("rwxr-x---", mode("run.sh"));
    }

    // 2.3 没读过：拒绝，让模型先读
    @Test
    void overwritingAnUnreadFileIsRefused() throws Exception {
        Files.writeString(root.resolve("a.txt"), "keep");
        ToolOutput output = write("a.txt", "lost");
        assertTrue(output.error());
        assertTrue(output.text().contains("has not been read in this session") && output.text().contains("server restarts"), output.text());
        assertEquals("keep", Files.readString(root.resolve("a.txt")));
    }

    // 2.4 读过之后被改过：拒绝，让模型重读
    @Test
    void overwritingAFileChangedSinceTheReadIsRefused() throws Exception {
        Files.writeString(root.resolve("a.txt"), "v1");
        read("a.txt");
        Files.writeString(root.resolve("a.txt"), "v2 by someone else");
        ToolOutput output = write("a.txt", "v3");
        assertTrue(output.error());
        assertTrue(output.text().contains("changed since it was read"), output.text());
        assertEquals("v2 by someone else", Files.readString(root.resolve("a.txt")));
    }

    // C5：别的会话读过不算
    @Test
    void aReadInAnotherSessionDoesNotCount() throws Exception {
        Files.writeString(root.resolve("a.txt"), "v1");
        read.run(new ToolContext(context.principal(), new SessionId("two"), context.workspace()), args("path", "a.txt"));
        assertTrue(write("a.txt", "v2").error());
    }

    // 2.6 越界、memory/、目录、超过 10 MB
    @Test
    void writesOutsideTheRulesAreRefused() throws Exception {
        Files.writeString(temp.resolve("outside.txt"), "x");
        String outside = write("../outside.txt", "y").text();
        String missingOutside = write("../nope.txt", "y").text();
        assertEquals(outside.replace("outside", "X"), missingOutside.replace("nope", "X"));
        assertEquals("x", Files.readString(temp.resolve("outside.txt")));

        assertTrue(write("memory/notes.md", "x").text().contains("memory_write"));
        Files.createDirectory(root.resolve("dir"));
        assertEquals("cannot write \"dir\": it is a directory", write("dir", "x").text());
        assertTrue(write("big.txt", "x".repeat(WriteFileTool.MAX_BYTES + 1)).text().contains("larger than 10 MB"));
        assertFalse(Files.exists(root.resolve("big.txt")));
    }

    @Test
    void contentIsWrittenAsIs() throws Exception {
        write("win.txt", "a\r\nb\n﻿");
        assertArrayEquals("a\r\nb\n﻿".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(root.resolve("win.txt")));
    }

    @Test
    void anEmptyFileCanBeWritten() throws Exception {
        assertEquals("Created empty.txt (0 bytes).", write("empty.txt", "").text());
    }

    // C5：自己写完接着写不用重读
    @Test
    void ownWritesNeedNoRereading() throws Exception {
        write("a.txt", "v1");
        assertEquals("Updated a.txt (2 bytes).", write("a.txt", "v2").text());
        assertEquals("Edited a.txt: replaced 1 occurrence.", edit("a.txt", "v2", "v3").text());
    }

    // ---- edit_file ----

    private void readable(String path, String content) throws Exception {
        Files.write(root.resolve(path), content.getBytes(StandardCharsets.UTF_8));
        read(path);
    }

    // 3.1 正好一次：替换并返回改了几处
    @Test
    void aUniqueMatchIsReplaced() throws Exception {
        readable("App.java", "int a = 1;\nint b = 2;\n");
        assertEquals("Edited App.java: replaced 1 occurrence.", edit("App.java", "int b = 2;", "int b = 3;").text());
        assertEquals("int a = 1;\nint b = 3;\n", Files.readString(root.resolve("App.java")));
    }

    // 3.2 找不到：说明必须逐字匹配
    @Test
    void aMissingMatchExplainsExactness() throws Exception {
        readable("App.java", "int a = 1;\n");
        ToolOutput output = edit("App.java", "int a  = 1;", "x");
        assertTrue(output.error());
        assertTrue(output.text().contains("must match the file exactly, including whitespace and line breaks"), output.text());
    }

    // 3.3 多次且没有 replace_all：报次数，文件不变
    @Test
    void severalMatchesNeedMoreContextOrReplaceAll() throws Exception {
        readable("a.txt", "x\nx\nx\n");
        ToolOutput output = edit("a.txt", "x", "y");
        assertTrue(output.text().contains("occurs 3 times") && output.text().contains("replace_all"), output.text());
        assertEquals("x\nx\nx\n", Files.readString(root.resolve("a.txt")));
    }

    // 3.4 replace_all：全部替换，返回几处
    @Test
    void replaceAllReplacesEveryMatch() throws Exception {
        readable("a.txt", "x\nx\nx\n");
        assertEquals("Edited a.txt: replaced 3 occurrences.", edit("a.txt", "x", "y", "replace_all", true).text());
        assertEquals("y\ny\ny\n", Files.readString(root.resolve("a.txt")));
    }

    // 3.5 空、相同：报错，文件不变
    @Test
    void emptyOrUnchangedEditsAreRefused() throws Exception {
        readable("a.txt", "x");
        assertTrue(edit("a.txt", "", "y").text().contains("old_string is empty"));
        assertTrue(edit("a.txt", "x", "x").text().contains("are the same"));
        assertEquals("x", Files.readString(root.resolve("a.txt")));
    }

    // 3.6 CRLF 和 BOM：匹配不受影响，写回后保持原样
    @Test
    void crlfAndBomSurviveAnEdit() throws Exception {
        readable("win.txt", "﻿line one\r\nline two\r\nline three\r\n");
        assertEquals("Edited win.txt: replaced 1 occurrence.", edit("win.txt", "line one\nline two", "first\nsecond").text());
        assertArrayEquals("﻿first\r\nsecond\r\nline three\r\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(root.resolve("win.txt")));
    }

    // 3.6 参数里带 CRLF 也能匹配 LF 文件，写回仍是 LF
    @Test
    void crlfInTheArgumentsMatchesAnLfFile() throws Exception {
        readable("unix.txt", "a\nb\nc\n");
        edit("unix.txt", "a\r\nb", "x\r\ny");
        assertEquals("x\ny\nc\n", Files.readString(root.resolve("unix.txt")));
    }

    // 3.7 没读过、读过后被改过、二进制、不存在
    @Test
    void editsNeedAFreshReadOfAnExistingTextFile() throws Exception {
        Files.writeString(root.resolve("unread.txt"), "x");
        assertTrue(edit("unread.txt", "x", "y").text().contains("has not been read"));

        readable("stale.txt", "x");
        Files.writeString(root.resolve("stale.txt"), "x changed");
        assertTrue(edit("stale.txt", "x", "y").text().contains("changed since it was read"));

        Files.write(root.resolve("a.bin"), new byte[] {'x', 0});
        observations.record(context.principal(), context.session(), root.resolve("a.bin").toRealPath());
        assertEquals("cannot edit \"a.bin\": binary file", edit("a.bin", "x", "y").text());

        assertTrue(edit("missing.txt", "x", "y").text().startsWith("path not found in the workspace"));
    }

    // 3.7 改完接着改不用重读
    @Test
    void consecutiveEditsNeedNoRereading() throws Exception {
        readable("a.txt", "one two three");
        edit("a.txt", "one", "1");
        edit("a.txt", "two", "2");
        assertEquals("Edited a.txt: replaced 1 occurrence.", edit("a.txt", "three", "3").text());
        assertEquals("1 2 3", Files.readString(root.resolve("a.txt")));
    }

    // 3.8 写回保留权限
    @Test
    void anEditKeepsTheMode() throws Exception {
        readable("run.sh", "echo a\n");
        Files.setPosixFilePermissions(root.resolve("run.sh"), PosixFilePermissions.fromString("rwxr-xr-x"));
        read("run.sh");
        edit("run.sh", "echo a", "echo b");
        assertEquals("rwxr-xr-x", mode("run.sh"));
    }

    @Test
    void memoryCannotBeEdited() throws Exception {
        Files.createDirectory(root.resolve("memory"));
        readable("memory/note.md", "x");
        assertTrue(edit("memory/note.md", "x", "y").text().contains("memory_write"));
        assertEquals("x", Files.readString(root.resolve("memory/note.md")));
    }

    @Test
    void multiByteTextIsMatchedExactly() throws Exception {
        readable("zh.md", "第一行：你好😀\n第二行\n");
        edit("zh.md", "你好😀", "再见");
        assertEquals("第一行：再见\n第二行\n", Files.readString(root.resolve("zh.md")));
    }

    @Test
    void writeToolsAreExclusive() {
        assertFalse(write.isConcurrencySafe(args()));
        assertFalse(edit.isConcurrencySafe(args()));
    }
}
