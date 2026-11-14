package tantalum.local.tool;

import tantalum.local.tool.ShellTool.Bash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.shell.Shell;
import tantalum.local.shell.ShellKind;
import tantalum.local.shell.Tail;
import tantalum.value.json.Json;

/**
 * {@code bash} 的参数和结果（L14）：超时默认 120 秒、最多 600 秒并声明给服务器；工作目录必须在允许的目录里，越界和不存在同一句话；
 * 退出码和超时变成错误结果。shell 换成记录参数的假的；真的见 {@code LocalShellIT}。
 */
class BashTest {

    @TempDir
    Path temp;

    private AllowedDirs dirs;
    private Path work;
    private final List<Object[]> runs = new ArrayList<>();
    private Shell.Run next = new Shell.Run(0, new Tail.Text("", false, 0, 0), false);

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
            runs.add(new Object[] {command, workdir, timeout});
            return next;
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        work = Files.createDirectory(temp.toRealPath().resolve("work"));
        Files.createDirectory(work.resolve("sub"));
        Files.writeString(work.resolve("file.txt"), "x");
        Files.createDirectory(temp.toRealPath().resolve("outside"));
        dirs = new AllowedDirs(List.of(work));
    }

    private static Json args(Object... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put((String) pairs[i], pairs[i + 1] instanceof Integer number ? new Json.Num(BigDecimal.valueOf(number))
                    : new Json.Str((String) pairs[i + 1]));
        }
        return new Json.Obj(fields);
    }

    private LocalResult run(Json arguments) throws Exception {
        return new Bash().run(new LocalContext("s", dirs, new Observations(), fake), arguments);
    }

    @Test
    void theTimeoutDefaultsTo120AndIsCappedAt600() throws Exception {
        run(args("command", "make"));
        run(args("command", "make", "timeout", 5));
        run(args("command", "make", "timeout", 3600));
        assertEquals(List.of(Duration.ofSeconds(120), Duration.ofSeconds(5), Duration.ofSeconds(600)),
                runs.stream().map(call -> call[2]).toList());
        assertTrue(run(args("command", "make", "timeout", 0)).error());
        assertEquals(3, runs.size(), "an invalid timeout never runs");
    }

    /** 服务器按工具声明的超时等结果：命令的超时再多等一会儿，让沙箱的超时说明先送回来（L14、任务 12）。 */
    @Test
    void theServerWaitsLongerThanTheCommand() {
        Duration standard = Duration.ofSeconds(60);
        assertEquals(Duration.ofSeconds(135), new Bash().timeout(args("command", "x"), standard));
        assertEquals(Duration.ofSeconds(615), new Bash().timeout(args("command", "x", "timeout", 900), standard));
        assertEquals(standard, new Bash().timeout(args("command", "x", "timeout", -1), standard));
    }

    @Test
    void theWorkdirMustBeAnAllowedDirectory() throws Exception {
        run(args("command", "ls"));
        run(args("command", "ls", "workdir", "sub"));
        assertEquals(List.of(work, work.resolve("sub")), runs.stream().map(call -> call[1]).toList());

        LocalResult outside = run(args("command", "ls", "workdir", "../outside"));
        LocalResult missing = run(args("command", "ls", "workdir", "nowhere"));
        assertTrue(outside.error() && outside.text().startsWith(AllowedDirs.NOT_FOUND), outside.text());
        assertEquals(outside.text().replace("../outside", "nowhere"), missing.text(), "outside and missing read the same");
        assertTrue(run(args("command", "ls", "workdir", "file.txt")).error());
        assertTrue(run(args("command", " ")).error());
        assertEquals(2, runs.size());
    }

    @Test
    void exitCodesTimeoutsAndTruncationAreReported() throws Exception {
        next = new Shell.Run(2, new Tail.Text("boom", false, 1, 1), false);
        assertEquals(new LocalResult("boom\n\nCommand exited with code 2.", true, List.of()), run(args("command", "x")));

        next = new Shell.Run(-1, new Tail.Text("partial", true, 9000, 2000), true);
        LocalResult timedOut = run(args("command", "x", "timeout", 3));
        assertTrue(timedOut.error());
        assertEquals("partial\n\n[Output truncated: showing the last 2000 of 9000 lines (limit 2000 lines or 50 KB).]\n\n"
                + "Command timed out after 3 s. It and everything it started were stopped.", timedOut.text());

        next = new Shell.Run(0, new Tail.Text("", false, 0, 0), false);
        assertEquals(LocalResult.ok("(no output)"), run(args("command", "true")));
        assertEquals("x", runs.getFirst()[0], "the command goes to the sandbox as given");
    }

    /** 留头留尾时写明各留了几行、完整输出在哪、怎么读；退出码照旧写在最后（tool-scenarios-2 需求 6）。 */
    @Test
    void headAndTailAndTheFullOutputFileAreReported() throws Exception {
        Path output = Files.createDirectory(temp.toRealPath().resolve("output"));
        Path full = Files.writeString(output.resolve("command-output-1.log"), "all");
        dirs = dirs.withReadable(output);
        next = new Shell.Run(1, new Tail.Text("ERROR first\n[... 1000 lines omitted ...]\nlast", true, 3000, 2000, false, 200, full, false), false);
        LocalResult result = run(args("command", "x"));
        assertTrue(result.error());
        assertEquals("ERROR first\n[... 1000 lines omitted ...]\nlast\n\n[Output truncated: showing the first 200 and the last 1800 of "
                + "3000 lines (limit 2000 lines or 50 KB). Full output saved to " + full + "; read parts of it with read_file (offset "
                + "and limit) or search it with grep.]\n\nCommand exited with code 1.", result.text());
        // 模型照着路径能读到
        LocalResult read = new ReadFile().run(new LocalContext("s", dirs, new Observations()), args("path", full.toString()));
        assertTrue(!read.error() && read.text().contains("all"), read.text());
    }
}
