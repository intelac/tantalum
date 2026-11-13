package tantalum.local.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 真的跑命令（connector-jar 需求 4）：macOS、Linux 上是 bash，Windows（CI 的 windows-latest）上是 PowerShell。
 * 同一组用例按系统换写法：中文输出、引号、退出码、到时结束子孙进程、线程被打断时结束进程树、令牌不进命令。
 */
class LocalShellIT {

    @TempDir
    Path temp;

    private Shell shell;
    private boolean windows;

    @BeforeEach
    void setUp() {
        ShellKind kind = ShellKind.current();
        windows = kind == ShellKind.POWERSHELL;
        Map<String, String> env = new java.util.HashMap<>(System.getenv());
        env.put("TANTALUM_TOKEN", "tc_should_not_leak");
        shell = LocalShell.open(ShellFinder.system(), kind, CommandEnv.of(env, List.of()));
        assertTrue(shell.check().available(), "this computer has " + kind);
    }

    private Shell.Run run(String bash, String powershell, int seconds) throws Exception {
        return shell.run(windows ? powershell : bash, temp, Duration.ofSeconds(seconds));
    }

    @Test
    void chineseOutputComesBackAsUtf8() throws Exception {
        Shell.Run run = run("echo '你好，世界'", "Write-Output '你好，世界'", 30);
        assertEquals(0, run.exitCode());
        assertEquals("你好，世界", run.output().text().strip());
    }

    @Test
    void doubleQuotesInsideTheCommandSurvive() throws Exception {
        Shell.Run run = run("echo \"a \\\"b\\\" c\"", "Write-Output \"a `\"b`\" c\"", 30);
        assertEquals("a \"b\" c", run.output().text().strip());
    }

    @Test
    void exitCodeIsReported() throws Exception {
        assertEquals(3, run("exit 3", "exit 3", 30).exitCode());
    }

    @Test
    void runsInTheGivenDirectory() throws Exception {
        Files.writeString(temp.resolve("marker.txt"), "x");
        Shell.Run run = run("ls", "Get-ChildItem -Name", 30);
        assertTrue(run.output().text().contains("marker.txt"), run.output().text());
    }

    @Test
    void theTokenIsNotInTheCommandsEnvironment() throws Exception {
        Shell.Run run = run("echo \"[$TANTALUM_TOKEN]\"", "Write-Output \"[$env:TANTALUM_TOKEN]\"", 30);
        assertEquals("[]", run.output().text().strip());
    }

    @Test
    void timeoutStopsTheChildToo() throws Exception {
        Path pidFile = temp.resolve("child.pid");
        // 子进程记下自己的 pid，然后睡很久；到时以后它也要被结束
        String bash = "sleep 60 & echo $! > child.pid; wait";
        String powershell = "$p = Start-Process -PassThru -NoNewWindow powershell -ArgumentList '-NoProfile','-Command','Start-Sleep 60'; "
                + "Set-Content child.pid $p.Id; $p.WaitForExit()";
        Shell.Run run = run(bash, powershell, 3);
        assertTrue(run.timedOut());
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "the child was stopped");
    }

    @Test
    void interruptStopsTheProcessTree() throws Exception {
        Path pidFile = temp.resolve("child.pid");
        String bash = "sleep 60 & echo $! > child.pid; wait";
        String powershell = "$p = Start-Process -PassThru -NoNewWindow powershell -ArgumentList '-NoProfile','-Command','Start-Sleep 60'; "
                + "Set-Content child.pid $p.Id; $p.WaitForExit()";
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                shell.run(windows ? powershell : bash, temp, Duration.ofSeconds(60));
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        });
        for (int i = 0; i < 100 && !Files.exists(pidFile); i++) {
            Thread.sleep(100);
        }
        Thread.sleep(300);
        worker.interrupt();
        worker.join(Duration.ofSeconds(15));
        assertFalse(worker.isAlive());
        assertTrue(thrown.get() instanceof InterruptedException, String.valueOf(thrown.get()));
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "the child was stopped");
    }
}
