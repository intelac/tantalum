package tantalum.local.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.AllowedDirs;
import tantalum.value.json.Json;

/** 五个 Git 工具（local-workspace L5、需求 3.2）：输出、截断、拒绝当成选项的参数、不改仓库、不是仓库时报错。 */
class GitToolsTest {

    @TempDir
    Path temp;

    private Path repo;
    private LocalContext context;
    private final GitLog log = new GitLog();
    private final GitShow show = new GitShow();
    private final GitDiff diff = new GitDiff();
    private final GitBlame blame = new GitBlame();
    private final GitBranches branches = new GitBranches();

    @BeforeEach
    void setUp() throws Exception {
        temp = temp.toRealPath();
        repo = Files.createDirectory(temp.resolve("repo"));
        git("init", "-q", "-b", "main");
        Files.writeString(repo.resolve("App.java"), "class App {\n  int a = 1;\n}\n");
        Files.createDirectories(repo.resolve("docs"));
        Files.writeString(repo.resolve("docs/README.md"), "# Shop\n");
        commit("first: add app", "Alice");
        Files.writeString(repo.resolve("App.java"), "class App {\n  int a = 2;\n}\n");
        commit("second: change a", "Bob");
        context = new LocalContext("s", new AllowedDirs(List.of(repo)), new Observations());
    }

    /** 测试自己造仓库用的 git：不读用户的全局配置（签名、钩子），和被测工具无关。 */
    private String git(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private void commit(String message, String author) throws Exception {
        git("add", "-A");
        git("-c", "user.name=" + author, "-c", "user.email=" + author.toLowerCase() + "@example.com", "commit", "-q", "--no-verify",
                "-m", message);
    }

    private static Json args(Object... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put((String) pairs[i], switch (pairs[i + 1]) {
                case Integer n -> new Json.Num(BigDecimal.valueOf(n));
                case Boolean b -> new Json.Bool(b);
                default -> new Json.Str((String) pairs[i + 1]);
            });
        }
        return new Json.Obj(fields);
    }

    // ---- 输出 ----

    @Test
    void logListsCommitsNewestFirst() throws Exception {
        LocalResult output = log.run(context, args());
        assertFalse(output.error(), output.text());
        List<String> lines = output.text().lines().toList();
        assertEquals(2, lines.size(), output.text());
        assertTrue(lines.get(0).matches("[0-9a-f]{7,} \\d{4}-\\d{2}-\\d{2} Bob: second: change a"), lines.get(0));
        assertTrue(lines.get(1).endsWith("Alice: first: add app"), lines.get(1));
        assertEquals(1, log.run(context, args("count", 1)).text().lines().count());
        assertTrue(log.run(context, args("path", "docs")).text().endsWith("Alice: first: add app"));
        assertEquals(1, log.run(context, args("path", "docs")).text().lines().count());
        assertTrue(log.run(context, args("revision", "HEAD~1")).text().endsWith("Alice: first: add app"));
    }

    @Test
    void showGivesTheCommitAndAFileAtACommit() throws Exception {
        String commit = show.run(context, args("revision", "HEAD")).text();
        assertTrue(commit.contains("second: change a") && commit.contains("-  int a = 1;") && commit.contains("+  int a = 2;"), commit);
        assertTrue(commit.contains("App.java | 2 +-"), commit);
        assertEquals("class App {\n  int a = 1;\n}", show.run(context, args("revision", "HEAD~1:App.java")).text());
        assertEquals("Missing argument: revision", show.run(context, args()).text());
        String limited = show.run(context, args("revision", "HEAD~1", "path", "docs")).text();
        assertTrue(limited.contains("# Shop") && !limited.contains("class App"), limited);
    }

    @Test
    void diffShowsUnstagedStagedAndAgainstACommit() throws Exception {
        assertEquals("No changes.", diff.run(context, args()).text());
        Files.writeString(repo.resolve("App.java"), "class App {\n  int a = 3;\n}\n");
        String unstaged = diff.run(context, args()).text();
        assertTrue(unstaged.contains("+  int a = 3;"), unstaged);
        assertEquals("No changes.", diff.run(context, args("staged", true)).text());
        git("add", "App.java");
        assertEquals("No changes.", diff.run(context, args()).text());
        assertTrue(diff.run(context, args("staged", true)).text().contains("+  int a = 3;"));
        String sinceFirst = diff.run(context, args("revision", "HEAD~1")).text();
        assertTrue(sinceFirst.contains("-  int a = 1;") && sinceFirst.contains("+  int a = 3;"), sinceFirst);
        assertEquals("No changes.", diff.run(context, args("revision", "HEAD", "path", "docs")).text());
    }

    @Test
    void diffComparesTwoCommits() throws Exception {
        String range = diff.run(context, args("revision", "HEAD~1..HEAD")).text();
        assertTrue(range.contains("-  int a = 1;") && range.contains("+  int a = 2;"), range);
        assertEquals(range, diff.run(context, args("revision", "HEAD~1..")).text());
        assertEquals("No changes.", diff.run(context, args("revision", "HEAD~1..HEAD", "path", "docs")).text());
        assertEquals("revision ranges with ... are not supported; use A..B", diff.run(context, args("revision", "HEAD~1...HEAD")).text());
        assertEquals("staged cannot be used with a range A..B", diff.run(context, args("revision", "HEAD~1..", "staged", true)).text());
        assertEquals("revision must not start with -", diff.run(context, args("revision", "HEAD..--output=x")).text());
    }

    @Test
    void blameNamesTheCommitOfEachLine() throws Exception {
        String all = blame.run(context, args("path", "App.java")).text();
        List<String> lines = all.lines().toList();
        assertEquals(3, lines.size(), all);
        assertTrue(lines.get(0).contains("Alice") && lines.get(0).endsWith("class App {"), lines.get(0));
        assertTrue(lines.get(1).contains("Bob") && lines.get(1).endsWith("int a = 2;"), lines.get(1));
        String second = blame.run(context, args("path", "App.java", "start_line", 2, "end_line", 2)).text();
        assertEquals(1, second.lines().count());
        assertTrue(second.contains("Bob"), second);
        assertTrue(blame.run(context, args("path", "App.java", "revision", "HEAD~1", "start_line", 2)).text().contains("int a = 1;"));
        assertEquals("end_line must not be before start_line", blame.run(context, args("path", "App.java", "start_line", 3, "end_line", 2)).text());
        assertEquals("Not a file: docs", blame.run(context, args("path", "docs")).text());
        assertEquals("Missing argument: path", blame.run(context, args()).text());
    }

    @Test
    void branchesMarkTheCurrentOne() throws Exception {
        git("branch", "feature");
        String text = branches.run(context, args()).text();
        List<String> lines = text.lines().toList();
        assertEquals(2, lines.size(), text);
        assertTrue(lines.get(0).startsWith("  feature ") && lines.get(0).endsWith("second: change a"), lines.get(0));
        assertTrue(lines.get(1).startsWith("* main "), lines.get(1));
    }

    // ---- 截断 ----

    /** 2000 行或 50 KB，先到哪个算哪个，结尾写明截断（同 read_file 的上限）。 */
    @Test
    void longOutputIsCutAndSaysSo() throws Exception {
        Files.writeString(repo.resolve("long.txt"), IntStream.rangeClosed(1, 3000).mapToObj(i -> "line " + i)
                .collect(Collectors.joining("\n", "", "\n")));
        commit("third: long file", "Alice");
        String text = show.run(context, args("revision", "HEAD:long.txt")).text();
        assertTrue(text.startsWith("line 1\nline 2\n"));
        assertTrue(text.endsWith("line 2000\n\n[Output truncated after 2000 lines (limit 2000 lines or 50 KB). Narrow it with path, "
                + "revision or count.]"), text.substring(text.length() - 200));

        Files.writeString(repo.resolve("wide.txt"), ("x".repeat(999) + "\n").repeat(100));
        commit("fourth: wide file", "Alice");
        String wide = show.run(context, args("revision", "HEAD:wide.txt")).text();
        String content = wide.substring(0, wide.indexOf("\n\n[Output truncated"));
        assertEquals(51, content.lines().count());
        assertTrue(content.getBytes(StandardCharsets.UTF_8).length + 1 <= Git.MAX_BYTES);
    }

    // ---- 只接受结构化字段 ----

    /** 以 - 开头的提交号会被 git 当成选项（比如 --output 能写文件），一律拒绝，git 不会被调用。 */
    @Test
    void revisionsThatLookLikeOptionsAreRefused() throws Exception {
        Path written = temp.resolve("written.txt");
        for (LocalTool tool : List.<LocalTool>of(log, show, diff)) {
            LocalResult output = tool.run(context, args("revision", "--output=" + written));
            assertTrue(output.error(), tool.name());
            assertEquals("revision must not start with -", output.text());
        }
        assertEquals("revision must not start with -", blame.run(context, args("path", "App.java", "revision", "-L1,2")).text());
        assertEquals("revision must not contain control characters", log.run(context, args("revision", "HEAD\n--all")).text());
        assertEquals("revision must be a non-empty string", log.run(context, args("revision", " ")).text());
        assertEquals("count must be 1 or more", log.run(context, args("count", -5)).text());
        assertFalse(Files.exists(written));
    }

    /** 路径照 AllowedDirs 检查：越界和不存在同一句话，不会交给 git。 */
    @Test
    void pathsOutsideTheAllowedDirectoriesAreRefused() throws Exception {
        Files.writeString(temp.resolve("outside.txt"), "x");
        assertEquals(AllowedDirs.NOT_FOUND + "../outside.txt", log.run(context, args("path", "../outside.txt")).text());
        assertEquals(AllowedDirs.NOT_FOUND + "../nope.txt", blame.run(context, args("path", "../nope.txt")).text());
    }

    // ---- 不改仓库 ----

    /**
     * 每个工具跑完，.git 下所有文件的内容和修改时间都不变。工作区文件刚改过、另一个的时间被 touch 过（索引里的 stat 过期），这时
     * {@code git diff} 即使设了 GIT_OPTIONAL_LOCKS=0 也会改写 .git/index（这个测试先在那个实现上失败过），所以 git_diff 用底层命令。
     */
    @Test
    void noToolChangesTheRepository() throws Exception {
        Files.writeString(repo.resolve("App.java"), "class App {\n  int a = 9;\n}\n");
        Path readme = repo.resolve("docs/README.md");
        Files.setLastModifiedTime(readme, FileTime.fromMillis(Files.getLastModifiedTime(readme).toMillis() + 10_000));
        Map<String, String> before = snapshot();
        assertTrue(diff.run(context, args()).text().contains("int a = 9;"));
        diff.run(context, args("staged", true));
        diff.run(context, args("revision", "HEAD~1"));
        diff.run(context, args("revision", "HEAD~1..HEAD"));
        log.run(context, args());
        show.run(context, args("revision", "HEAD"));
        blame.run(context, args("path", "App.java"));
        branches.run(context, args("all", true));
        assertEquals(before, snapshot());
    }

    private Map<String, String> snapshot() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(repo.resolve(".git"))) {
            for (Path path : walk.sorted().toList()) {
                String state = Files.getLastModifiedTime(path).toMillis() + " " + (Files.isRegularFile(path) ? sha256(path) : "dir");
                files.put(repo.relativize(path).toString(), state);
            }
        }
        return files;
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ---- 不是仓库 ----

    @Test
    void outsideARepositoryEveryToolSaysSo() throws Exception {
        Path plain = Files.createDirectory(temp.resolve("plain"));
        Files.writeString(plain.resolve("a.txt"), "x");
        LocalContext none = new LocalContext("s", new AllowedDirs(List.of(plain)), new Observations());
        for (LocalTool tool : List.<LocalTool>of(log, diff, branches)) {
            assertEquals(Git.NOT_A_REPOSITORY + ".", tool.run(none, args()).text(), tool.name());
        }
        assertEquals(Git.NOT_A_REPOSITORY + "a.txt", blame.run(none, args("path", "a.txt")).text());
        assertEquals(Git.NOT_A_REPOSITORY + ".", show.run(none, args("revision", "HEAD")).text());
    }

    /**
     * B1（2026-11-06）：主目录不是仓库也能查。每次调用按路径找仓库：主目录下子文件夹里的仓库、另一个允许目录里的仓库都行，
     * 不在仓库里的路径报“不是 Git 仓库”。{@code hello.git} 只说这台电脑装没装 git。
     */
    @Test
    void eachCallFindsTheRepositoryOfItsPath() throws Exception {
        Path main = Files.createDirectory(temp.resolve("projects"));
        Files.writeString(main.resolve("notes.txt"), "x");
        Path nested = Files.createDirectory(main.resolve("shop"));
        Files.move(repo, nested.resolve("repo"));
        Path inner = nested.resolve("repo");
        Path other = Files.createDirectory(temp.resolve("other"));
        LocalContext plainMain = new LocalContext("s", new AllowedDirs(List.of(main, other)), new Observations());
        assertTrue(GitCheck.installed(main));

        LocalResult sub = log.run(plainMain, args("path", "shop/repo"));
        assertFalse(sub.error(), sub.text());
        assertEquals(2, sub.text().lines().count());
        assertTrue(blame.run(plainMain, args("path", "shop/repo/App.java")).text().contains("int a = 2"));
        assertEquals(Git.NOT_A_REPOSITORY + ".", log.run(plainMain, args()).text(), "the main directory itself is not a repository");
        assertEquals(Git.NOT_A_REPOSITORY + "notes.txt", log.run(plainMain, args("path", "notes.txt")).text());

        // 第二个允许目录里的仓库
        Files.move(inner, other.resolve("repo"));
        LocalResult second = log.run(plainMain, args("path", other.resolve("repo").toString()));
        assertFalse(second.error(), second.text());
        assertTrue(second.text().contains("Bob: second: change a"));
    }

    /** 只允许了仓库里的一个子目录：仓库顶层在允许的目录外，git show 能读到外面的文件，所以当作不是仓库。 */
    @Test
    void aRepositoryWhoseTopIsOutsideTheAllowedDirectoriesIsNotUsed() throws Exception {
        LocalContext docsOnly = new LocalContext("s", new AllowedDirs(List.of(repo.resolve("docs"))), new Observations());
        LocalResult output = show.run(docsOnly, args("revision", "HEAD:App.java"));
        assertTrue(output.error());
        assertEquals(Git.NOT_A_REPOSITORY + ".", output.text());
    }

    @Test
    void badRevisionsComeBackAsGitErrors() throws Exception {
        LocalResult output = show.run(context, args("revision", "no-such-branch"));
        assertTrue(output.error());
        assertTrue(output.text().startsWith("git show failed: "), output.text());
    }

    /** 取消（线程被打断）时不等 git 跑完：抛出中断，git 进程被结束（L8）。 */
    @Test
    void anInterruptedCallStopsGit() throws Exception {
        long before = ProcessHandle.current().descendants().count();
        Thread.currentThread().interrupt();
        try {
            org.junit.jupiter.api.Assertions.assertThrows(InterruptedException.class, () -> log.run(context, args()));
        } finally {
            Thread.interrupted();
        }
        // 结束是异步的，给系统回收一点时间
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count() > before && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(before, ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count());
    }

    @Test
    void gitToolsAreReadOnlyAndConcurrent() {
        for (LocalTool tool : List.<LocalTool>of(log, show, diff, blame, branches)) {
            assertEquals(LocalTool.Kind.GIT, tool.kind());
            assertTrue(tool.isConcurrencySafe(args()), tool.name());
        }
    }
}
