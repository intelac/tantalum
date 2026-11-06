package tantalum.local.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.fs.PrivateFiles;
import tantalum.value.json.Json;

/** 从服务器上的 {@code BuiltinToolsTest} 搬来：read_file 和 grep 的基本行为，断言不变。 */
class ReadGrepTest {

    /** 只在 Windows 上跳过的原因。 */
    static final String MAC_CONNECTOR = "连接程序目前只在 macOS 上运行：Windows 上显示的路径用 \\ 分隔，怎么显示归 Windows 连接程序 spec";

    @TempDir
    Path temp;

    private Path root;
    private ReadFile read;
    private Grep grep;
    private LocalContext context;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectory(temp.resolve("root"));
        Files.writeString(root.resolve("a.cbl"), "COMPUTE INTEREST = 1\nMOVE X TO Y\n");
        Files.createDirectory(root.resolve("sub"));
        Files.writeString(root.resolve("sub/b.cbl"), "COMPUTE TAX\n; rm -rf /tmp/x\n");
        Files.writeString(temp.resolve("secret.txt"), "COMPUTE SECRET");
        context = new LocalContext("s", new AllowedDirs(List.of(root)), new Observations());
        read = new ReadFile();
        grep = new Grep();
    }

    private static Json args(String... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put(pairs[i], new Json.Str(pairs[i + 1]));
        }
        return new Json.Obj(fields);
    }

    @Test
    void readReturnsTheFile() throws Exception {
        LocalResult output = read.run(context, args("path", "a.cbl"));
        assertFalse(output.error());
        assertTrue(output.text().contains("COMPUTE INTEREST"));
    }

    @Test
    void readRejectsEscapes() throws Exception {
        LocalResult output = read.run(context, args("path", "../secret.txt"));
        assertTrue(output.error());
        assertFalse(output.text().contains("SECRET"));
    }

    @Test
    void readReportsMissingArgument() throws Exception {
        assertTrue(read.run(context, args()).error());
    }

    @Test
    void readReportsADirectoryAsAnError() throws Exception {
        assertTrue(read.run(context, args("path", "sub")).error());
    }

    @Test
    void grepFindsMatchesWithFileAndLine() throws Exception {
        LocalResult output = grep.run(context, args("pattern", "COMPUTE"));
        assertFalse(output.error());
        assertTrue(output.text().contains("a.cbl:1:COMPUTE INTEREST"), output.text());
        assertTrue(output.text().contains("b.cbl:1:COMPUTE TAX"), output.text());
        assertFalse(output.text().contains("SECRET"));
    }

    @Test
    void grepTreatsShellCharactersAsPlainPattern() throws Exception {
        LocalResult output = grep.run(context, args("pattern", "; rm -rf /tmp/x"));
        assertFalse(output.error());
        assertTrue(output.text().contains("b.cbl:2"), output.text());
        assertFalse(Files.exists(Path.of("/tmp/x-should-not-exist")));
    }

    @Test
    void grepCanBeLimitedToASubdirectory() throws Exception {
        LocalResult output = grep.run(context, args("pattern", "COMPUTE", "path", "sub"));
        assertFalse(output.text().contains("a.cbl"));
    }

    @Test
    void grepRejectsEscapesAndBadPatterns() throws Exception {
        assertTrue(grep.run(context, args("pattern", "x", "path", "..")).error());
        assertTrue(grep.run(context, args("pattern", "(")).error());
        assertTrue(grep.run(context, args()).error());
    }

    @Test
    void grepSkipsSymlinksLeadingOutside() throws Exception {
        Files.createSymbolicLink(root.resolve("link.txt"), temp.resolve("secret.txt"));
        assertFalse(grep.run(context, args("pattern", "SECRET")).text().contains("SECRET"));
    }

    @Test
    void grepReportsNoMatches() throws Exception {
        LocalResult output = grep.run(context, args("pattern", "NOPE"));
        assertFalse(output.error());
        assertEquals("No matches.", output.text());
    }

    /** 模型要知道哪些参数不能省：schema 里要有 required 数组，可选参数不在里面。 */
    @Test
    void schemasDeclareRequiredArguments() {
        Json.Obj readSchema = (Json.Obj) read.schema();
        assertEquals(new Json.Arr(java.util.List.of(new Json.Str("path"))), readSchema.fields().get("required"));
        Json.Obj grepSchema = (Json.Obj) grep.schema();
        assertEquals(new Json.Arr(java.util.List.of(new Json.Str("pattern"))), grepSchema.fields().get("required"));
        assertTrue(((Json.Obj) grepSchema.fields().get("properties")).fields().containsKey("path"));
    }

    @Test
    void grepTruncatesLongLines() throws Exception {
        Files.writeString(root.resolve("minified.js"), "COMPUTE" + "x".repeat(5000) + "\n");
        LocalResult output = grep.run(context, args("pattern", "COMPUTE"));
        assertFalse(output.error(), output.text());
        assertTrue(output.text().contains("a.cbl:1:COMPUTE INTEREST"), output.text());
        for (String line : output.text().split("\n")) {
            assertTrue(line.length() <= Grep.MAX_LINE_CHARS + 40, "line too long: " + line.length());
        }
    }

    @Test
    void grepSkipsUnreadableFiles() throws Exception {
        Files.writeString(root.resolve("minified.js"), "COMPUTE" + "x".repeat(5000) + "\n");
        Path locked = root.resolve("locked.cbl");
        Files.writeString(locked, "COMPUTE LOCKED\n");
        assumeTrue(PrivateFiles.posix(locked), "读不了的文件用 POSIX 权限造；Windows 上没有这个办法，读失败的处理和平台无关，在 macOS 上测");
        Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        try {
            LocalResult output = grep.run(context, args("pattern", "COMPUTE"));
            assertFalse(output.error(), output.text());
            assertTrue(output.text().contains("a.cbl:1:COMPUTE INTEREST"), "readable files still searched");
            assertFalse(output.text().contains("LOCKED"));
            for (String line : output.text().split("\n")) {
                assertTrue(line.length() <= Grep.MAX_LINE_CHARS + 40, "line too long: " + line.length());
            }
        } finally {
            Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
    }

    /** 错误信息不能带电脑上的绝对路径。 */
    @Test
    void errorsDoNotRevealAbsolutePaths() throws Exception {
        LocalResult outside = read.run(context, args("path", "../secret.txt"));
        assertFalse(outside.text().contains(temp.toString()), outside.text());
        Path locked = root.resolve("locked.cbl");
        Files.writeString(locked, "x");
        assumeTrue(PrivateFiles.posix(locked), "读不了的文件用 POSIX 权限造；Windows 上没有这个办法，读失败的处理和平台无关，在 macOS 上测");
        Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        try {
            LocalResult output = read.run(context, args("path", "locked.cbl"));
            assertTrue(output.error());
            assertFalse(output.text().contains(root.toString()), output.text());
        } finally {
            Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
    }

    /** 1 MB 的整文件上限去掉了（core-tools C4），改成 64 MB；空字节开头的文件按二进制拒绝。 */
    @Test
    void readRejectsBinaryFiles() throws Exception {
        Files.write(root.resolve("big.bin"), new byte[1024 * 1024 + 1]);
        LocalResult output = read.run(context, args("path", "big.bin"));
        assertTrue(output.error());
        assertTrue(output.text().contains("Binary file"));
    }

    @Test
    void builtinsAreSafeToRunTogether() {
        assertTrue(read.isConcurrencySafe(args()));
        assertTrue(grep.isConcurrencySafe(args()));
    }

    /** 其他允许目录里的匹配写绝对路径，能原样交给 read_file；主目录里的照旧写相对路径。 */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = MAC_CONNECTOR)
    void grepInAnotherAllowedDirectoryShowsAbsolutePaths() throws Exception {
        Path other = Files.createDirectory(temp.resolve("other")).toRealPath();
        Files.writeString(other.resolve("c.cbl"), "COMPUTE OTHER\n");
        LocalContext two = new LocalContext("s", new AllowedDirs(List.of(root, other)), new Observations());
        assertEquals(other.resolve("c.cbl") + ":1:COMPUTE OTHER", grep.run(two, args("pattern", "COMPUTE", "path", other.toString())).text());
        assertTrue(grep.run(two, args("pattern", "COMPUTE")).text().contains("sub/b.cbl:1:COMPUTE TAX"));
        assertTrue(grep.run(context, args("pattern", "COMPUTE", "path", other.toString())).error());
    }
}
