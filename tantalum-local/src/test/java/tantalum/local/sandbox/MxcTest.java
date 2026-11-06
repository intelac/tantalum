package tantalum.local.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.AllowedDirs;

/**
 * 找不到或用不了 MXC 时（L13、需求 4.3）：不可用，原因写清楚，{@code run} 也不执行。用真实 MXC 的测试见 {@code MxcIT}。
 */
class MxcTest {

    @TempDir
    Path temp;

    private MxcPolicy policy;

    @BeforeEach
    void setUp() throws Exception {
        Path work = Files.createDirectory(temp.toRealPath().resolve("work"));
        policy = MxcPolicy.of(new AllowedDirs(List.of(work)), false, List.of(), true, List.of(), Map.of());
    }

    private static boolean mac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    @Test
    void withoutTheProgramTheReasonSaysHowToGetIt() {
        assumeTrue(mac(), "MXC 只在 macOS 上找");
        Sandbox.Status status = Mxc.open(null, temp.resolve("missing/mxc-exec-mac"), policy).check();
        assertFalse(status.available());
        // console-i18n 任务 11：原因是代码加参数，给模型和老服务器的是英文
        assertEquals(Unavailable.of(Unavailable.Code.MXC_MISSING), status.why());
        assertTrue(status.reason().contains("cannot find the MXC") && status.reason().contains("--mxc"), status.reason());

        Sandbox.Status explicit = Mxc.open(temp.resolve("nowhere"), null, policy).check();
        assertFalse(explicit.available());
        assertEquals(Unavailable.of(Unavailable.Code.MXC_INVALID, "path", temp.resolve("nowhere").toString()), explicit.why());
        assertTrue(explicit.reason().contains("--mxc") && explicit.reason().contains(temp.resolve("nowhere").toString()), explicit.reason());
    }

    /** 程序在，但不是能用的 MXC（这里拿 /usr/bin/false 冒充）：第一步检查就不通过，不会去跑命令。 */
    @Test
    void aProgramThatFailsTheCheckIsNotUsed() throws Exception {
        assumeTrue(mac(), "MXC 只在 macOS 上找");
        Sandbox sandbox = Mxc.open(Path.of("/usr/bin/false"), null, policy);
        Sandbox.Status status = sandbox.check();
        assertFalse(status.available());
        assertEquals(Unavailable.Code.CHECK_FAILED, status.why().code());
        assertEquals("exit code 1", status.why().params().get("detail"), "MXC 没有输出时 detail 是英文的退出码");
        assertTrue(status.reason().startsWith("MXC's policy check failed"), status.reason());
        assertEquals(status, sandbox.check(), "检查一次就记下");
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> sandbox.run("echo hi", temp, Duration.ofSeconds(5)));
        assertTrue(refused.getMessage().contains("not available"), refused.getMessage());
        sandbox.close();
    }

    @Test
    void anUnavailableSandboxNeverRuns() {
        Sandbox none = Sandbox.unavailable(Unavailable.of(Unavailable.Code.SHELL_NOT_ALLOWED));
        assertEquals("--allow-shell was not given", none.check().reason(), "老版本服务器和控制台按这句认");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> none.run("echo hi", temp, Duration.ofSeconds(1)));
        assertEquals("The command sandbox is not available: --allow-shell was not given", refused.getMessage(), "给模型的那句是英文");
    }

    /** 非 macOS 的条件下：原因是 {@code sandbox/not-macos}。 */
    @Test
    void notMacOsHasItsOwnCode() {
        String before = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Linux");
            Sandbox.Status status = Mxc.open(null, null, policy).check();
            assertEquals("sandbox/not-macos", status.why().code().wire());
            assertEquals("MXC's sandbox program mxc-exec-mac only runs on macOS", status.reason());
        } finally {
            System.setProperty("os.name", before);
        }
    }
}
