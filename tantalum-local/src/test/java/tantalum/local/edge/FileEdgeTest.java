package tantalum.local.edge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.tool.EditFile;
import tantalum.local.tool.FindFiles;
import tantalum.local.tool.Grep;
import tantalum.local.tool.ListDir;
import tantalum.local.tool.LocalContext;
import tantalum.local.tool.LocalResult;
import tantalum.local.tool.Observations;
import tantalum.local.tool.ReadFile;
import tantalum.local.tool.WriteFile;
import tantalum.value.json.Json;

/**
 * 文件工具的边界（agent-eval 需求 1.3–1.5）：直接调连接程序里真的工具，不经过服务器和模型。每条测试名是那个边界的一句话。
 * 已经由 {@code WriteEditToolsTest}、{@code ReadFileWindowTest}、{@code TextFilesTest} 覆盖的（CRLF 和 BOM 改完不变、长行截断、
 * 多字节、窗口读、过期保护）不再重复。
 */
class FileEdgeTest {

    @TempDir
    Path temp;

    private Path root;
    private Path outside;
    private LocalContext context;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectory(temp.resolve("root")).toRealPath();
        outside = Files.createDirectory(temp.resolve("outside")).toRealPath();
        context = new LocalContext("one", new AllowedDirs(List.of(root)), new Observations());
    }

    private static Json args(String... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put(pairs[i], new Json.Str(pairs[i + 1]));
        }
        return new Json.Obj(fields);
    }

    private LocalResult read(String path) {
        return new ReadFile().run(context, args("path", path));
    }

    private LocalResult edit(String path, String from, String to) {
        return new EditFile().run(context, args("path", path, "old_string", from, "new_string", to));
    }

    private LocalResult grep(String pattern) {
        return new Grep().run(context, args("pattern", pattern));
    }

    private static boolean symlinks(Path dir) {
        try {
            Path probe = Files.createSymbolicLink(dir.resolve(".probe-link"), dir);
            Files.delete(probe);
            return true;
        } catch (IOException | UnsupportedOperationException notAllowed) {
            return false;
        }
    }

    private static final String NO_SYMLINKS = "Windows 上没有开发者模式时不能建符号链接";

    // ---- 读：编码 ----

    @Test
    void aGbkFileIsRefusedWithItsEncodingAndAWayOut() throws Exception {
        Files.write(root.resolve("gbk.txt"), "价格 = 100\n名字 = 苹果\n".getBytes(Charset.forName("GBK")));
        LocalResult result = read("gbk.txt");
        assertTrue(result.error());
        assertEquals("Not a UTF-8 text file: gbk.txt (looks like GBK). read_file and edit_file only handle UTF-8; "
                + "to read it, convert a copy with a shell command such as iconv.", result.text());
    }

    @Test
    void aUtf16FileIsNotCalledBinary() throws Exception {
        Files.write(root.resolve("u16.txt"), "hello\nworld\n".getBytes(StandardCharsets.UTF_16));
        LocalResult result = read("u16.txt");
        assertTrue(result.error());
        assertTrue(result.text().startsWith("Not a UTF-8 text file: u16.txt (looks like UTF-16)."), result.text());
    }

    @Test
    void aGbkFileCannotBeEditedSoItIsNeverCorrupted() throws Exception {
        byte[] original = "价格 = 100\n".getBytes(Charset.forName("GBK"));
        Files.write(root.resolve("gbk.txt"), original);
        read("gbk.txt");
        assertTrue(edit("gbk.txt", "100", "200").error());
        assertArrayEquals(original, Files.readAllBytes(root.resolve("gbk.txt")));
    }

    /** 只有 CR 的老 Mac 换行不算换行（只认 LF 和 CRLF）：读出来是一行，CR 原样留着，改文件时照样能精确匹配。 */
    @Test
    void oldMacLineEndingsStayOneLineWithTheCrKept() throws Exception {
        Files.writeString(root.resolve("cr.txt"), "a\rb\rc");
        assertEquals("a\rb\rc", read("cr.txt").text());
        assertFalse(edit("cr.txt", "b\rc", "B").error());
        assertEquals("a\rB", Files.readString(root.resolve("cr.txt")));
    }

    @Test
    void aFileOfOnlyNewlinesIsNotEmpty() throws Exception {
        Files.writeString(root.resolve("nl.txt"), "\n\n\n");
        LocalResult result = read("nl.txt");
        assertFalse(result.error());
        assertEquals("\n\n", result.text());
    }

    @Test
    void aUnicodeFileNameWithSpacesReads() throws Exception {
        Files.writeString(root.resolve("名字 带空格.txt"), "unicode name\n");
        assertEquals("unicode name", read("名字 带空格.txt").text());
    }

    // ---- 改 ----

    @Test
    void anEditKeepsAMissingFinalNewlineMissing() throws Exception {
        Files.writeString(root.resolve("noeol.txt"), "one\ntwo");
        read("noeol.txt");
        assertFalse(edit("noeol.txt", "two", "three").error());
        assertEquals("one\nthree", Files.readString(root.resolve("noeol.txt")));
    }

    @Test
    void replacingTheWholeFileWithNothingLeavesAnEmptyFile() throws Exception {
        Files.writeString(root.resolve("whole.txt"), "all of it\n");
        read("whole.txt");
        assertFalse(edit("whole.txt", "all of it\n", "").error());
        assertEquals(0, Files.size(root.resolve("whole.txt")));
    }

    /** 不做 Unicode 正规化：写法不同的同一个字找不到，报“没找到”而不是改错地方（pi、DSH 都是精确匹配）。 */
    @Test
    void aDecomposedAccentDoesNotMatchAComposedOne() throws Exception {
        Files.writeString(root.resolve("nfc.txt"), "café\n");
        read("nfc.txt");
        LocalResult result = edit("nfc.txt", "café", "tea");
        assertTrue(result.error());
        assertTrue(result.text().contains("old_string was not found"), result.text());
        assertEquals("café\n", Files.readString(root.resolve("nfc.txt")));
    }

    /** 随机序列测试（种子 6）发现的：重叠的两处都能对上时，改哪一处是猜的，所以拒绝，不再默默改第一处。 */
    @Test
    void anOldStringThatMatchesTwiceOverlappingIsAmbiguous() throws Exception {
        Files.writeString(root.resolve("o.txt"), "needle needle needle\n");
        read("o.txt");
        LocalResult result = edit("o.txt", "e needle", "X");
        assertTrue(result.error());
        assertEquals("cannot edit \"o.txt\": old_string occurs 2 times (some of them overlap). Include more surrounding lines to make "
                + "it unique, or set replace_all to true.", result.text());
        assertEquals("needle needle needle\n", Files.readString(root.resolve("o.txt")));
        // 代码里常见的样子
        Files.writeString(root.resolve("braces.txt"), "  }\n}\n}\n");
        read("braces.txt");
        assertTrue(edit("braces.txt", "}\n}", "}").error());
    }

    @Test
    void editingThroughALinkInsideChangesTheTargetAndKeepsTheLink() throws Exception {
        assumeTrue(symlinks(root), NO_SYMLINKS);
        Files.writeString(root.resolve("target.txt"), "inside\n");
        Files.createSymbolicLink(root.resolve("link.txt"), root.resolve("target.txt"));
        read("link.txt");
        assertFalse(edit("link.txt", "inside", "changed").error());
        assertEquals("changed\n", Files.readString(root.resolve("target.txt")));
        assertTrue(Files.isSymbolicLink(root.resolve("link.txt")));
    }

    @Test
    void aLinkToAFileOutsideCanNeitherBeReadNorWritten() throws Exception {
        assumeTrue(symlinks(root), NO_SYMLINKS);
        Files.writeString(outside.resolve("secret.txt"), "secret\n");
        Files.createSymbolicLink(root.resolve("out.txt"), outside.resolve("secret.txt"));
        assertTrue(read("out.txt").error());
        assertTrue(new WriteFile().run(context, args("path", "out.txt", "content", "pwned\n")).error());
        assertEquals("secret\n", Files.readString(outside.resolve("secret.txt")));
        assertFalse(read("out.txt").text().contains(outside.toString()), "errors do not reveal the real path");
    }

    // ---- 搜 ----

    @Test
    void grepSkipsBinaryFiles() throws Exception {
        Files.write(root.resolve("bin.dat"), new byte[] {0, 1, 2, 'n', 'e', 'e', 'd', 'l', 'e', 0});
        Files.writeString(root.resolve("text.txt"), "needle\n");
        assertEquals("text.txt:1:needle", grep("needle").text());
    }

    @Test
    void outsideARepositoryGrepSkipsVcsAndDependencyDirectoriesLikeFindFiles() throws Exception {
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "needle = 1\n");
        Files.createDirectories(root.resolve("web/node_modules/pkg"));
        Files.writeString(root.resolve("web/node_modules/pkg/index.js"), "needle\n");
        Files.writeString(root.resolve("web/app.js"), "needle\n");
        Files.writeString(root.resolve(".hidden.txt"), "needle\n");
        assertEquals(".hidden.txt:1:needle\nweb/app.js:1:needle", sorted(grep("needle").text()));
    }

    @Test
    void inARepositoryGrepSkipsWhatGitIgnores() throws Exception {
        assumeTrue(git(root, "init", "-q"), "没装 git");
        Files.writeString(root.resolve(".gitignore"), "build/\n*.log\n");
        Files.createDirectories(root.resolve("build"));
        Files.writeString(root.resolve("build/out.txt"), "needle\n");
        Files.writeString(root.resolve("debug.log"), "needle\n");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/tracked.txt"), "needle\n");
        assertTrue(git(root, "add", "src/tracked.txt"));
        Files.writeString(root.resolve("src/untracked.txt"), "needle\n");
        Files.writeString(root.resolve(".hidden.txt"), "needle\n");
        assertEquals(".hidden.txt:1:needle\nsrc/tracked.txt:1:needle\nsrc/untracked.txt:1:needle", sorted(grep("needle").text()));
        // 从子目录搜也一样
        assertEquals("src/tracked.txt:1:needle\nsrc/untracked.txt:1:needle",
                sorted(new Grep().run(context, args("pattern", "needle", "path", "src")).text()));
        // 点名一个被忽略的文件：照搜（是用户要的）
        assertEquals("debug.log:1:needle", new Grep().run(context, args("pattern", "needle", "path", "debug.log")).text());
    }

    /** find_files 有意连被忽略的文件一起找（DSH glob 的 --no-ignore --hidden），只跳过版本库目录和 node_modules。 */
    @Test
    void findFilesStillListsIgnoredFiles() throws Exception {
        assumeTrue(git(root, "init", "-q"), "没装 git");
        Files.writeString(root.resolve(".gitignore"), "build/\n");
        Files.createDirectories(root.resolve("build"));
        Files.writeString(root.resolve("build/out.txt"), "x\n");
        assertEquals("build/out.txt", new FindFiles().run(context, args("pattern", "*.txt")).text());
    }

    @Test
    void aSymlinkLoopDoesNotHangFindOrGrep() throws Exception {
        assumeTrue(symlinks(root), NO_SYMLINKS);
        Files.createDirectories(root.resolve("loop"));
        Files.createSymbolicLink(root.resolve("loop/back"), root);
        Files.writeString(root.resolve("a.txt"), "needle\n");
        assertEquals("a.txt", new FindFiles().run(context, args("pattern", "*.txt")).text());
        assertEquals("a.txt:1:needle", grep("needle").text());
        assertTrue(new ListDir().run(context, args("path", "loop")).text().contains("back"));
    }

    @Test
    void regexMetacharactersWorkAndABadPatternSaysWhy() throws Exception {
        Files.writeString(root.resolve("r.txt"), "a.b (x) [y] $z\n");
        assertEquals("r.txt:1:a.b (x) [y] $z", grep("\\(x\\)").text());
        LocalResult bad = grep("[unclosed");
        assertTrue(bad.error());
        assertTrue(bad.text().startsWith("Invalid pattern: Unclosed character class"), bad.text());
    }

    private static String sorted(String lines) {
        return String.join("\n", lines.lines().sorted().toList());
    }

    private static boolean git(Path dir, String... arguments) {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com"));
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
            process.getOutputStream().close();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException noGit) {
            return false;
        }
    }
}
