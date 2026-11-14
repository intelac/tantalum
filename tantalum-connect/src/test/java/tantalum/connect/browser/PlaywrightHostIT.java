package tantalum.connect.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.shell.CommandEnv;
import tantalum.local.tool.LocalResult;
import tantalum.value.json.Json;

/**
 * 看着 Playwright MCP 的那一层（{@link PlaywrightHost}、{@link PlaywrightProcess}），用 {@link StdioTestServer} 代替真的 Playwright MCP：
 * 它是一个 Java 程序，CI 上不用 Node。启动、调用、图片、错误、环境变量、工作目录、崩溃后重连、次数用完、关闭时整棵树都结束；
 * Windows 上再经 {@code .cmd} 启动一次（{@code npx} 就是 {@code .cmd}）。原来是本机 MCP 的 {@code LocalMcpIT}（connector-jar 需求 6、9.1），
 * remove-generic-mcp 后这些只给浏览器用，测试留着。真的 Playwright MCP 和浏览器在 {@link PlaywrightMcpIT}。
 */
class PlaywrightHostIT {

    @TempDir
    Path temp;

    private PlaywrightHost host;

    @AfterEach
    void tearDown() {
        if (host != null) {
            host.close();
        }
    }

    private static List<String> javaCommand(String... extra) {
        List<String> line = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"), "-cp",
                System.getProperty("java.class.path"), StdioTestServer.class.getName()));
        line.addAll(List.of(extra));
        return line;
    }

    private PlaywrightHost start(Duration first, int attempts, List<String> line, Path cwd, String marker) {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("GITHUB_TOKEN", "gh-secret");
        Map<String, String> passed = new HashMap<>(CommandEnv.of(env, List.of()));
        passed.put("MCP_TEST", marker);
        host = new PlaywrightHost(new ServerCommand(line.getFirst(), line.subList(1, line.size())), passed, cwd, first, first.multipliedBy(4),
                attempts);
        host.start();
        return host;
    }

    private void await(Supplier<Boolean> condition, String what) throws InterruptedException {
        for (int i = 0; i < 600; i++) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(what + "; state " + host.state() + ", reason " + host.reason());
    }

    private void awaitReady() throws InterruptedException {
        await(() -> host.state() == PlaywrightHost.State.READY, "never became ready");
    }

    private static Json args(String key, String value) {
        return new Json.Obj(Map.of(key, new Json.Str(value)));
    }

    private static final Json NONE = new Json.Obj(Map.of());

    @Test
    void startsAndCalls() throws Exception {
        Path work = Files.createDirectory(temp.resolve("srv"));
        start(Duration.ofMillis(200), 3, javaCommand(), work, "given");
        awaitReady();
        assertEquals(List.of("echo", "secret", "image", "fail", "child", "exit"), host.tools().stream().map(tool -> tool.name()).toList());

        LocalResult echo = host.call("echo", args("text", "你好"));
        assertFalse(echo.error());
        assertEquals("srv|given|你好", echo.text(), "runs in its directory with the given variables");

        assertEquals("null", host.call("secret", NONE).text(), "secret-looking variables are not passed");

        LocalResult image = host.call("image", NONE);
        assertEquals("one pixel", image.text());
        assertEquals(1, image.images().size());
        assertEquals("image/png", image.images().getFirst().mime());
        assertTrue(java.util.Arrays.equals(StdioTestServer.PNG, image.images().getFirst().bytes()));

        LocalResult fail = host.call("fail", NONE);
        assertTrue(fail.error());
        assertEquals("it failed", fail.text());

        assertTrue(host.call("no-such-tool", NONE).error());
    }

    @Test
    void restartsAfterACrash() throws Exception {
        start(Duration.ofMillis(200), 3, javaCommand(), temp, "given");
        awaitReady();
        assertTrue(host.call("exit", NONE).error(), "the call fails as the server dies");
        await(() -> host.state() == PlaywrightHost.State.STARTING, "did not notice the crash");
        awaitReady();
        assertTrue(host.call("echo", args("text", "again")).text().endsWith("|given|again"));
    }

    @Test
    void givesUpAfterTheAttemptsAndSaysWhy() throws Exception {
        start(Duration.ofMillis(50), 3, javaCommand("broken"), temp, "given");
        await(() -> host.state() == PlaywrightHost.State.FAILED, "did not give up");
        assertTrue(host.reason().contains("broken on purpose"), host.reason());
        LocalResult call = host.call("echo", NONE);
        assertTrue(call.error());
        assertTrue(call.text().contains("broken on purpose"), call.text());
    }

    @Test
    void closingStopsTheWholeTree() throws Exception {
        start(Duration.ofMillis(200), 3, javaCommand(), temp, "given");
        awaitReady();
        assertFalse(host.call("child", NONE).error());
        long pid = Long.parseLong(Files.readString(temp.resolve("child.pid")).strip());
        assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        host.close();
        host = null;
        Thread.sleep(500);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "the server's child was stopped too");
    }

    /** Windows 上 {@code npx} 这类是 {@code .cmd}：经 {@code cmd.exe /d /s /c} 起（connector-jar 需求 6.2）。 */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aCmdScriptStartsOnWindows() throws Exception {
        List<String> java = javaCommand();
        Path script = temp.resolve("test-server.cmd");
        Files.writeString(script, "@echo off\r\n\"" + java.get(0) + "\" -cp \"" + java.get(2) + "\" " + java.get(3) + " %*\r\n");
        start(Duration.ofMillis(200), 3, List.of(script.toString()), temp, "cmd");
        awaitReady();
        assertTrue(host.call("echo", args("text", "hi")).text().endsWith("|cmd|hi"));
    }
}
