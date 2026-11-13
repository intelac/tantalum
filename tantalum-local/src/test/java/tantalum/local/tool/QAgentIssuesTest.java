package tantalum.local.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.AllowedDirs;
import tantalum.value.json.Json;

/**
 * 对照 QAgent 的问题清单（2026-11-14 Yi 发来）在本机工具上复现的问题。每个测试名前的编号是清单里的编号。
 */
class QAgentIssuesTest {

    @TempDir
    Path temp;

    private static Json args(String... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put(pairs[i], new Json.Str(pairs[i + 1]));
        }
        return new Json.Obj(fields);
    }

    private LocalContext context(Path... dirs) throws java.io.IOException {
        return new LocalContext("s", new AllowedDirs(List.of(dirs)), new Observations());
    }

    /** 39：一个读不了的子目录让整次搜索失败（“Search failed: AccessDeniedException”），别处的匹配也丢了。 */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void issue39_anUnreadableDirectoryIsSkippedNotFatal() throws Exception {
        Path root = temp.toRealPath();
        Files.writeString(root.resolve("a.txt"), "needle here\n");
        Path locked = Files.createDirectory(root.resolve("locked"));
        Files.writeString(locked.resolve("b.txt"), "needle\n");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            LocalResult result = new Grep().run(context(root), args("pattern", "needle"));
            assertFalse(result.error(), result.text());
            assertTrue(result.text().contains("a.txt"), result.text());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /** 53：glob 以 ! 开头（排除这些文件，ripgrep 的写法）时一个文件都不搜，还说“没有匹配”。 */
    @Test
    void issue53_aGlobStartingWithBangExcludes() throws Exception {
        Path root = temp.toRealPath();
        Files.writeString(root.resolve("A.java"), "needle\n");
        Files.writeString(root.resolve("b.txt"), "needle\n");
        LocalResult result = new Grep().run(context(root), args("pattern", "needle", "glob", "!*.java"));
        assertFalse(result.error(), result.text());
        assertTrue(result.text().contains("b.txt"), result.text());
        assertFalse(result.text().contains("A.java"), result.text());
    }

    /** 84（一）：没给 path、主目录不是仓库时，提示写的是“.”，模型不知道是哪个目录。 */
    @Test
    void issue84_theHintNamesTheRealDirectory() throws Exception {
        Path plain = Files.createDirectory(temp.toRealPath().resolve("plain"));
        LocalResult result = new GitLog().run(context(plain), args());
        assertTrue(result.error());
        assertTrue(result.text().contains(plain.toString()), result.text());
    }

    /** 84（二）：允许的目录在一个更大的仓库里（仓库顶层在允许范围外）时，说“不是 Git 仓库”，其实是仓库在范围外。 */
    @Test
    void issue84_aRepositoryWhoseTopIsOutsideSaysSo() throws Exception {
        Path top = temp.toRealPath();
        git(top, "init", "-q", "-b", "main");
        Path inside = Files.createDirectory(top.resolve("inside"));
        LocalResult result = new GitLog().run(context(inside), args());
        assertTrue(result.error());
        assertFalse(result.text().startsWith(Git.NOT_A_REPOSITORY), result.text());
        assertTrue(result.text().contains(top.toString()), result.text());
    }

    /** 6：git_branches 按名字排序，最近提交过的分支反而排在最后。 */
    @Test
    void issue6_branchesComeNewestFirst() throws Exception {
        Path repo = temp.toRealPath();
        git(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("a"), "1");
        commit(repo, "2020-01-01T00:00:00Z");
        git(repo, "branch", "aaa-old");
        Files.writeString(repo.resolve("a"), "2");
        commit(repo, "2026-01-01T00:00:00Z");
        git(repo, "branch", "zzz-new");
        git(repo, "checkout", "-q", "aaa-old");
        Files.writeString(repo.resolve("a"), "0");
        commit(repo, "2010-01-01T00:00:00Z");
        LocalResult result = new GitBranches().run(context(repo), args());
        String text = result.text();
        assertTrue(text.indexOf("zzz-new") < text.indexOf("aaa-old"), text);
        assertTrue(text.indexOf("main") < text.indexOf("aaa-old"), text);
    }

    private static void commit(Path repo, String date) throws Exception {
        git(repo, Map.of("GIT_COMMITTER_DATE", date, "GIT_AUTHOR_DATE", date), "-c", "user.name=t", "-c", "user.email=t@example.com",
                "commit", "-q", "--no-verify", "-am", "c " + date);
    }

    private static void git(Path repo, String... arguments) throws Exception {
        git(repo, Map.of(), arguments);
    }

    private static void git(Path repo, Map<String, String> env, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(arguments));
        if (arguments.length > 0 && arguments[arguments.length - 1].startsWith("c ")) {
            // 第一次提交要先 add
            new ProcessBuilder("git", "add", "-A").directory(repo.toFile()).start().waitFor();
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true);
        builder.environment().putAll(env);
        builder.environment().put("GIT_CONFIG_GLOBAL", repo.resolve(".no-global").toString());
        Process process = builder.start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
}
