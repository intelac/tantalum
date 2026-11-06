package tantalum.local.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** core-tools 需求 4 的验收标准，逐条对应。 */
class ListFindToolsTest {

    @TempDir
    Path temp;

    private Path root;
    private LocalContext context;
    private final ListDir list = new ListDir();
    private final FindFiles find = new FindFiles();

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectory(temp.resolve("root"));
        context = new LocalContext("one", new AllowedDirs(List.of(root)), new Observations());
    }

    private static Json args(String... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put(pairs[i], new Json.Str(pairs[i + 1]));
        }
        return new Json.Obj(fields);
    }

    private void files(String... paths) throws Exception {
        for (String path : paths) {
            Path file = root.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "x");
        }
    }

    // 4.1 根下一层，排序不分大小写，目录加 /，包括点开头的
    @Test
    void listShowsOneLevelSortedWithDirectoriesMarked() throws Exception {
        files("b.txt", "A.md", ".env", "src/Main.java", "docs/x.md");
        assertEquals(".env\nA.md\nb.txt\ndocs/\nsrc/", list.run(context, args()).text());
        assertEquals("Main.java", list.run(context, args("path", "src")).text());
    }

    @Test
    void listSaysWhenADirectoryIsEmpty() throws Exception {
        Files.createDirectory(root.resolve("empty"));
        assertEquals("(empty directory)", list.run(context, args("path", "empty")).text());
    }

    // 4.2 超过 500 项只列前 500 并写总数
    @Test
    void listStopsAt500Entries() throws Exception {
        for (int i = 0; i < 520; i++) {
            Files.writeString(root.resolve(String.format("f%03d", i)), "");
        }
        String text = list.run(context, args()).text();
        assertTrue(text.startsWith("f000\nf001\n"));
        assertTrue(text.endsWith("f499\n\n[Showing 500 of 520 entries.]"), text.substring(text.length() - 60));
    }

    // 4.3 不带 / 匹配任意深度的文件名；带 / 按相对路径匹配
    @Test
    void aPatternWithoutSlashMatchesNamesAtAnyDepth() throws Exception {
        files("A.java", "src/main/B.java", "src/test/BTest.java", "README.md");
        assertEquals("A.java\nsrc/main/B.java\nsrc/test/BTest.java", find.run(context, args("pattern", "*.java")).text());
    }

    @Test
    void aPatternWithSlashMatchesRelativePaths() throws Exception {
        files("src/ATest.java", "src/test/BTest.java", "src/main/B.java", "other/CTest.java");
        assertEquals("src/ATest.java\nsrc/test/BTest.java", find.run(context, args("pattern", "src/**/*Test.java")).text());
        assertEquals("src/main/B.java", find.run(context, args("pattern", "main/*.java", "path", "src")).text());
        assertEquals("other/CTest.java\nsrc/ATest.java\nsrc/test/BTest.java", find.run(context, args("pattern", "**/*Test.java")).text());
    }

    // 4.4 跳过版本库和 node_modules，不跟随符号链接，只返回文件
    @Test
    void findSkipsRepositoriesDependenciesAndLinks() throws Exception {
        files("a.js", ".git/hooks/b.js", "node_modules/lib/c.js", "web/node_modules/d.js", ".hg/e.js", ".svn/f.js", "lib/g.js");
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.js"), "x");
        Files.createSymbolicLink(root.resolve("linked"), outside);
        Files.createSymbolicLink(root.resolve("alias.js"), root.resolve("a.js"));
        Files.createDirectory(root.resolve("dir.js"));
        assertEquals("a.js\nlib/g.js", find.run(context, args("pattern", "*.js")).text());
    }

    // 4.5 超过 1000 个只给前 1000 并写总数；没有结果写明
    @Test
    void findStopsAt1000AndSaysSo() throws Exception {
        for (int i = 0; i < 1010; i++) {
            files(String.format("d/f%04d.txt", i));
        }
        String text = find.run(context, args("pattern", "*.txt")).text();
        assertTrue(text.endsWith("d/f0999.txt\n\n[Showing 1000 of 1010 files. Narrow the pattern or the path.]"));
        assertEquals("No files found.", find.run(context, args("pattern", "*.nothing")).text());
    }

    // 4.6 越界或不是目录：和 read_file 一样的说法
    @Test
    void escapesAndNonDirectoriesAreRefusedLikeReadFile() throws Exception {
        files("a.txt");
        String readSays = new ReadFile().run(context, args("path", "../x")).text();
        assertEquals(readSays, list.run(context, args("path", "../x")).text());
        assertEquals(readSays, find.run(context, args("pattern", "*", "path", "../x")).text());
        assertEquals("Not a directory: a.txt", list.run(context, args("path", "a.txt")).text());
        assertEquals("Not a directory: a.txt", find.run(context, args("pattern", "*", "path", "a.txt")).text());
    }

    @Test
    void badPatternsAreReported() {
        LocalResult output = find.run(context, args("pattern", "[a"));
        assertTrue(output.error());
        assertTrue(output.text().startsWith("Invalid pattern"), output.text());
        assertTrue(find.run(context, args()).error());
    }

    @Test
    void resultsAreSortedByPath() throws Exception {
        files(IntStream.of(3, 1, 2).mapToObj(i -> "z" + i + "/a.txt").toArray(String[]::new));
        assertEquals(IntStream.rangeClosed(1, 3).mapToObj(i -> "z" + i + "/a.txt").collect(Collectors.joining("\n")),
                find.run(context, args("pattern", "a.txt")).text());
    }

    // ---- 多个允许的目录（L12） ----

    /** 其他允许目录用绝对路径列、找；找到的路径是绝对路径，能原样交给 read_file。 */
    @Test
    void otherAllowedDirectoriesAreListedAndSearchedByAbsolutePath() throws Exception {
        Path other = Files.createDirectory(temp.resolve("other")).toRealPath();
        Files.createDirectories(other.resolve("lib"));
        Files.writeString(other.resolve("lib/Util.java"), "x");
        files("src/Main.java");
        LocalContext two = new LocalContext("one", new AllowedDirs(List.of(root, other)), new Observations());
        assertEquals("lib/", list.run(two, args("path", other.toString())).text());
        String found = find.run(two, args("pattern", "*.java", "path", other.toString())).text();
        assertEquals(other.resolve("lib/Util.java").toString(), found);
        assertEquals("x", new ReadFile().run(two, args("path", found)).text());
        // 不给 path 时只找主目录
        assertEquals("src/Main.java", find.run(two, args("pattern", "*.java")).text());
        // 只允许主目录时同样的绝对路径越界
        assertTrue(list.run(context, args("path", other.toString())).text().startsWith(AllowedDirs.NOT_FOUND));
    }
}
