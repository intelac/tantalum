package tantalum.local.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.tool.LocalContext;
import tantalum.local.tool.LocalResult;
import tantalum.local.tool.LocalTool;
import tantalum.local.tool.Observations;
import tantalum.local.tool.Tools;
import tantalum.value.json.Json;

/**
 * 命令行的边界（agent-eval 需求 1.2）：真的 MXC 沙箱，只在 macOS 上、程序存在时跑（同 {@link MxcIT}）。Windows 上还没有命令行工具
 * （Windows 连接程序的 PowerShell 是以后的 spec），所以这里不在 Windows CI 上跑。
 */
class BashEdgeIT {

    @TempDir
    Path temp;

    private Path work;
    private Sandbox sandbox;

    @BeforeAll
    static void needsMxc() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("mac"), "MXC 的 macOS 沙箱只在 macOS 上测");
        assumeTrue(Files.isExecutable(MxcIT.CACHED), "没有 " + MxcIT.CACHED + "：见 MxcIT");
    }

    @BeforeEach
    void setUp() throws Exception {
        work = Files.createDirectory(temp.toRealPath().resolve("work"));
        sandbox = Mxc.open(MxcIT.CACHED, null, MxcPolicy.of(new AllowedDirs(List.of(work)), true, List.of(), true, List.of(), Map.of()));
        assertTrue(sandbox.check().available());
    }

    @AfterEach
    void tearDown() {
        sandbox.close();
    }

    private LocalResult bash(String command, int... timeout) throws Exception {
        Map<String, Json> fields = new java.util.LinkedHashMap<>();
        fields.put("command", new Json.Str(command));
        if (timeout.length > 0) {
            fields.put("timeout", new Json.Num(java.math.BigDecimal.valueOf(timeout[0])));
        }
        LocalTool tool = Tools.all().stream().filter(each -> each.name().equals("bash")).findFirst().orElseThrow();
        return tool.run(new LocalContext("s", new AllowedDirs(List.of(work)), new Observations(), sandbox), new Json.Obj(fields));
    }

    @Test
    void binaryOutputReachesTheModelWithoutControlCharacters() throws Exception {
        LocalResult result = bash("printf 'a\\000b\\001c\\377d\\n'");
        assertFalse(result.error());
        assertEquals("abc�d", result.text());
    }

    @Test
    void stdoutAndStderrComeBackInTheOrderTheyWereWritten() throws Exception {
        assertEquals("out1\nerr1\nout2\nerr2", bash("for i in 1 2; do echo out$i; echo err$i >&2; done").text());
    }

    @Test
    void aSingleHugeLineSaysItWasCut() throws Exception {
        LocalResult result = bash("head -c 300000 /dev/zero | tr '\\0' 'x'");
        assertFalse(result.error());
        assertTrue(result.text().endsWith("\n\n[Output truncated: line 1 alone is longer than 50 KB; showing only its end (limit 2000 lines or 50 KB).]"),
                result.text().substring(result.text().length() - 150));
        assertTrue(result.text().startsWith("xxxx"));
    }

    @Test
    void aProgressBarAndColorsAreCleanedUp() throws Exception {
        assertEquals("100%\nred", bash("printf '10%%\\r50%%\\r100%%\\n\\033[31mred\\033[0m\\n'").text());
    }

    @Test
    void outputWithoutAFinalNewlineIsKept() throws Exception {
        assertEquals("no newline", bash("printf 'no newline'").text());
    }

    @Test
    void aCommandWaitingForInputGetsEndOfInputAndDoesNotHang() throws Exception {
        long start = System.nanoTime();
        assertEquals("got:", bash("read x; echo got:$x", 30).text());
        assertTrue(System.nanoTime() - start < 10_000_000_000L);
    }

    /**
     * 放到后台的进程在命令返回后照常跑完（同 pi：只有超时和取消才结束整个进程组）；它拿着输出管道时命令最多再等 2 秒就返回，
     * 不会一直挂着。它的输出不再收集。
     */
    @Test
    void aBackgroundJobKeepsRunningButDoesNotHoldTheCommand() throws Exception {
        long start = System.nanoTime();
        assertEquals("started", bash("(sleep 3; echo late > late.txt) & echo started").text());
        assertTrue(System.nanoTime() - start < 3_000_000_000L, "returned before the background job finished");
        for (int i = 0; i < 50 && !Files.exists(work.resolve("late.txt")); i++) {
            Thread.sleep(100);
        }
        assertTrue(Files.exists(work.resolve("late.txt")), "the background job ran to the end");
        long quick = System.nanoTime();
        assertEquals("started2", bash("nohup sleep 2 >/dev/null 2>&1 & echo started2").text());
        assertTrue(System.nanoTime() - quick < 1_500_000_000L, "a job that let go of the output does not delay the command");
    }

    @Test
    void aFailingCommandKeepsTheEndOfALongOutputAndTheExitCode() throws Exception {
        LocalResult result = bash("seq 1 3000; exit 7");
        assertTrue(result.error());
        assertTrue(result.text().startsWith("1001\n"));
        assertTrue(result.text().endsWith("3000\n\n[Output truncated: showing the last 2000 of 3000 lines (limit 2000 lines or 50 KB).]"
                + "\n\nCommand exited with code 7."), result.text().substring(result.text().length() - 200));
    }

    @Test
    void aTimeoutAfterLotsOfOutputStillSaysItTimedOut() throws Exception {
        LocalResult result = bash("seq 1 100000; sleep 10", 2);
        assertTrue(result.error());
        assertTrue(result.text().endsWith("Command timed out after 2 s. It and everything it started were stopped."));
        assertTrue(result.text().contains("100000\n"));
    }

    @Test
    void unicodeFileNamesAndChangingDirectoryWork() throws Exception {
        assertEquals("名字 空格.txt\nsub", bash("echo hi > '名字 空格.txt' && mkdir sub && ls").text());
        assertTrue(bash("cd sub && pwd").text().endsWith("/work/sub"));
    }

    @Test
    void hugeStderrIsCappedLikeStdout() throws Exception {
        LocalResult result = bash("head -c 200000 /dev/zero | tr '\\0' 'e' >&2; echo; echo done");
        assertTrue(result.text().contains("done"));
        assertTrue(result.text().length() < 60_000, "capped at about 50 KB: " + result.text().length());
    }
}
