package tantalum.connect.mcp;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.shell.CommandEnv;
import tantalum.local.tool.LocalResult;
import tantalum.local.wire.McpServerState;
import tantalum.local.wire.McpServerState.State;
import tantalum.value.json.Json;

/**
 * 真的起本机 MCP 服务器（connector-jar 需求 6、9.1）：{@link StdioTestServer} 是一个 Java 程序，CI 上不用 Node。
 * 启动、清单、调用、图片、错误、环境变量、工作目录、崩溃后重连、次数用完、退出时整棵树都结束；Windows 上再经 {@code .cmd} 启动一次。
 */
class LocalMcpIT {

    @TempDir
    Path temp;

    private final List<McpServerState> reports = new CopyOnWriteArrayList<>();
    private McpHost host;

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

    private McpConfig.Entry entry(String name, List<String> line, Path cwd) {
        return new McpConfig.Entry(name, line.getFirst(), line.subList(1, line.size()), Map.of("MCP_TEST", "from-config"), cwd, false);
    }

    private McpHost start(Duration first, int attempts, McpConfig.Entry... entries) {
        Map<String, String> host = new HashMap<>(System.getenv());
        host.put("GITHUB_TOKEN", "gh-secret");
        this.host = new McpHost(new McpConfig(List.of(entries), List.of()), CommandEnv.of(host, List.of()), temp, reports::add,
                first, first.multipliedBy(4), attempts);
        this.host.start();
        return this.host;
    }

    private McpServerState await(String name, Predicate<McpServerState> condition) throws InterruptedException {
        for (int i = 0; i < 600; i++) {
            for (McpServerState state : reports) {
                if (state.name().equals(name) && condition.test(state)) {
                    return state;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no report for " + name + " matched; got " + reports);
    }

    private static Json args(String key, String value) {
        return new Json.Obj(Map.of(key, new Json.Str(value)));
    }

    private static final Json NONE = new Json.Obj(Map.of());

    @Test
    void startsListsAndCalls() throws Exception {
        Path work = Files.createDirectory(temp.resolve("srv"));
        start(Duration.ofMillis(200), 3, entry("test", javaCommand(), work));
        McpServerState ready = await("test", state -> state.state() == State.READY);
        assertEquals(List.of("echo", "secret", "image", "fail", "child", "exit"), ready.tools().stream().map(McpServerState.McpToolSpec::name).toList());
        assertEquals("test tool echo", ready.tools().getFirst().description());

        LocalResult echo = host.call("mcp:test:echo", args("text", "你好"));
        assertFalse(echo.error());
        assertEquals("srv|from-config|你好", echo.text(), "runs in its cwd with the configured env");

        assertEquals("null", host.call("mcp:test:secret", NONE).text(), "secret-looking variables are not passed");

        LocalResult image = host.call("mcp:test:image", NONE);
        assertEquals("one pixel", image.text());
        assertEquals(1, image.images().size());
        assertEquals("image/png", image.images().getFirst().mime());
        assertTrue(java.util.Arrays.equals(StdioTestServer.PNG, image.images().getFirst().bytes()));

        LocalResult fail = host.call("mcp:test:fail", NONE);
        assertTrue(fail.error());
        assertEquals("it failed", fail.text());

        assertTrue(host.call("mcp:nope:echo", NONE).error());
        assertTrue(host.call("mcp:test:no-such-tool", NONE).error());
    }

    @Test
    void reconnectsAfterACrashKeepingItsTools() throws Exception {
        start(Duration.ofMillis(200), 3, entry("test", javaCommand(), temp));
        await("test", state -> state.state() == State.READY);
        reports.clear();
        assertTrue(host.call("mcp:test:exit", NONE).error(), "the call fails as the server dies");
        McpServerState restarting = await("test", state -> state.state() == State.STARTING);
        assertFalse(restarting.tools().isEmpty(), "tools stay while reconnecting");
        await("test", state -> state.state() == State.READY);
        assertEquals("temp|from-config|again", host.call("mcp:test:echo", args("text", "again")).text().replaceFirst("^[^|]*\\|", "temp|"));
    }

    @Test
    void givesUpAfterTheAttemptsAndSaysWhy() throws Exception {
        start(Duration.ofMillis(50), 3, entry("broken", javaCommand("broken"), temp));
        McpServerState failed = await("broken", state -> state.state() == State.FAILED);
        assertTrue(failed.tools().isEmpty());
        assertTrue(failed.reason().contains("broken on purpose"), failed.reason());
        assertTrue(host.call("mcp:broken:echo", NONE).error());
    }

    @Test
    void closingStopsTheWholeTree() throws Exception {
        start(Duration.ofMillis(200), 3, entry("test", javaCommand(), temp));
        await("test", state -> state.state() == State.READY);
        assertFalse(host.call("mcp:test:child", NONE).error());
        long pid = Long.parseLong(Files.readString(temp.resolve("child.pid")).strip());
        assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        host.close();
        host = null;
        Thread.sleep(500);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "the server's child was stopped too");
    }

    @Test
    void disabledServersDoNotStart() throws Exception {
        McpConfig.Entry off = new McpConfig.Entry("off", "does-not-exist", List.of(), Map.of(), null, true);
        McpHost idle = start(Duration.ofMillis(50), 1, off);
        assertEquals(State.DISABLED, idle.states().getFirst().state());
        Thread.sleep(300);
        assertTrue(reports.isEmpty());
    }

    /** Windows 上 {@code npx} 这类是 {@code .cmd}：经 {@code cmd.exe /d /s /c} 起（需求 6.2）。 */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aCmdScriptStartsOnWindows() throws Exception {
        List<String> java = javaCommand();
        Path script = temp.resolve("test-server.cmd");
        Files.writeString(script, "@echo off\r\n\"" + java.get(0) + "\" -cp \"" + java.get(2) + "\" " + java.get(3) + " %*\r\n");
        start(Duration.ofMillis(200), 3, new McpConfig.Entry("cmd", script.toString(), List.of(), Map.of("MCP_TEST", "cmd"), null, false));
        await("cmd", state -> state.state() == State.READY);
        assertTrue(host.call("mcp:cmd:echo", args("text", "hi")).text().endsWith("|cmd|hi"));
    }
}
