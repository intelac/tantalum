package tantalum.local.tool;

import tantalum.local.tool.ShellTool.Bash;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.shell.Shell;
import tantalum.local.shell.ShellKind;
import tantalum.local.shell.Tail;
import tantalum.value.json.Json;

/**
 * 控制台把 {@code bash} 和 {@code grep} 的结果文字拆开来画成终端卡片和搜索卡片，靠的是这两个工具写结果的固定句式。
 * 这里用真的工具跑出结果，和控制台解析器测试用的同一批样本文件逐字比较：谁改了句式，这个测试先红，
 * 不会等到页面上的卡片悄悄退回普通显示才发现。样本在 {@code console/src/process/tool/fixtures}。
 */
class ResultTextContractTest {

    private static final Path FIXTURES = Path.of("..", "console", "src", "process", "tool", "fixtures");

    @TempDir
    Path temp;

    private Path work;
    private AllowedDirs dirs;
    private Shell.Run next;

    private final Shell fake = new Shell() {
        @Override
        public Status check() {
            return Status.ok();
        }

        @Override
        public ShellKind kind() {
            return ShellKind.BASH;
        }

        @Override
        public Run run(String command, Path workdir, Duration timeout) {
            return next;
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        work = Files.createDirectory(temp.toRealPath().resolve("work"));
        dirs = new AllowedDirs(List.of(work));
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(FIXTURES.resolve(name), StandardCharsets.UTF_8);
    }

    private static Json args(Object... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put((String) pairs[i], pairs[i + 1] instanceof Integer number ? new Json.Num(BigDecimal.valueOf(number))
                    : new Json.Str((String) pairs[i + 1]));
        }
        return new Json.Obj(fields);
    }

    private String bash(int exitCode, Tail.Text output, boolean timedOut) throws Exception {
        next = new Shell.Run(exitCode, output, timedOut);
        return new Bash().run(LocalContextFixture.of("s", dirs, new Observations(), fake), args("command", "x", "timeout", 5)).text();
    }

    private static Tail.Text whole(String text) {
        return new Tail.Text(text, false, 0, 0);
    }

    private static Tail.Text cut(String text) {
        return new Tail.Text(text, true, 4321, 2000);
    }

    private String grep(String pattern) {
        return new Grep().run(LocalContextFixture.of("s", dirs, new Observations()), args("pattern", pattern)).text();
    }

    @Test
    void bashResultsReadTheWayTheConsoleExpects() throws Exception {
        assertEquals(fixture("bash-ok.txt"), bash(0, whole("line one\nline two"), false));
        assertEquals(fixture("bash-empty.txt"), bash(0, whole(""), false));
        assertEquals(fixture("bash-exit.txt"), bash(2, whole("boom"), false));
        assertEquals(fixture("bash-exit-silent.txt"), bash(1, whole(""), false));
        assertEquals(fixture("bash-timeout.txt"), bash(-1, whole("partial"), true));
        assertEquals(fixture("bash-truncated.txt"), bash(0, cut("tail"), false));
        assertEquals(fixture("bash-truncated-exit.txt"), bash(3, cut("tail"), false));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = ReadGrepTest.MAC_CONNECTOR)
    void grepMatchesReadTheWayTheConsoleExpects() throws Exception {
        Files.createDirectory(work.resolve("sub"));
        Files.writeString(work.resolve("sub/a.txt"), "alpha COMPUTE\nskip\nCOMPUTE: again 12:34\n");
        assertEquals(fixture("grep-matches.txt"), grep("COMPUTE"));
    }

    @Test
    void grepWithoutMatchesAndAtTheCapReadTheWayTheConsoleExpects() throws Exception {
        assertEquals(fixture("grep-none.txt"), grep("NOPE"));
        StringBuilder big = new StringBuilder();
        for (int i = 1; i <= Grep.MAX_MATCHES + 50; i++) {
            big.append("hit ").append(i).append('\n');
        }
        Files.writeString(work.resolve("big.txt"), big.toString());
        assertEquals(fixture("grep-capped.txt"), grep("hit"));
    }
}
