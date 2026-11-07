package tantalum.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.tool.LocalContext;
import tantalum.local.tool.LocalResult;
import tantalum.local.tool.LocalTool;
import tantalum.local.tool.Tools;
import tantalum.local.wire.Call;
import tantalum.local.wire.Context;
import tantalum.local.wire.ContextResult;
import tantalum.local.wire.Result;
import tantalum.value.json.Json;

/** local-workspace L4、L8、L15、需求 2.3、2.5：电脑这边不信任服务器，每个调用都查；取消和断开时打断；记录里没有文件内容。 */
class CallsTest {

    private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-06T03:02:13Z"), ZoneOffset.UTC);

    @TempDir
    Path temp;

    private Path work;
    private Path outside;
    private AllowedDirs dirs;
    private final ByteArrayOutputStream terminal = new ByteArrayOutputStream();
    private final Journal journal = new Journal(new PrintStream(terminal, true, StandardCharsets.UTF_8), NOON);
    private final BlockingQueue<Result> replies = new LinkedBlockingQueue<>();

    @BeforeEach
    void dirs() throws Exception {
        temp = temp.toRealPath();
        work = Files.createDirectory(temp.resolve("work"));
        outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(work.resolve("a.txt"), "SECRET-CONTENT hello\n");
        Files.writeString(outside.resolve("b.txt"), "outside\n");
        dirs = new AllowedDirs(List.of(work));
    }

    private Calls calls(boolean write) {
        Calls calls = new Calls(dirs, write, false, journal, Tools.all());
        calls.welcome("alice");
        return calls;
    }

    private static Call call(String id, String user, String tool, Object... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put((String) pairs[i], new Json.Str((String) pairs[i + 1]));
        }
        return new Call(id, user, "s-1a2b3c4d5e", tool, new Json.Obj(fields), 60_000);
    }

    private Result reply() throws InterruptedException {
        Result result = replies.poll(5, TimeUnit.SECONDS);
        if (result == null) {
            throw new AssertionError("no reply");
        }
        return result;
    }

    private String printed() {
        return terminal.toString(StandardCharsets.UTF_8);
    }

    /** 需求 2.3：没有 {@code --allow-write}，服务器硬发 {@code write_file} 也被拒，文件没写出来，终端记 denied。 */
    @Test
    void aWriteWithoutAllowWriteIsRefused() throws Exception {
        Calls calls = calls(false);
        calls.call(call("c-1", "alice", "write_file", "path", "new.txt", "content", "x"), replies::add);
        calls.call(call("c-2", "alice", "edit_file", "path", "a.txt", "old_string", "hello", "new_string", "bye"), replies::add);
        assertEquals(new Result("c-1", Calls.NO_WRITE, true, List.of()), reply());
        assertEquals(new Result("c-2", Calls.NO_WRITE, true, List.of()), reply());
        assertFalse(Files.exists(work.resolve("new.txt")));
        assertEquals("SECRET-CONTENT hello\n", Files.readString(work.resolve("a.txt")));
        assertTrue(printed().contains("write_file new.txt denied"), printed());
    }

    /** 有 {@code --allow-write} 时照常写（先读后写的规则在工具里）。 */
    @Test
    void aWriteWithAllowWriteWorks() throws Exception {
        Calls calls = calls(true);
        calls.call(call("c-1", "alice", "write_file", "path", "new.txt", "content", "x"), replies::add);
        assertFalse(reply().error());
        assertEquals("x", Files.readString(work.resolve("new.txt")));
    }

    /** L18：只接受发给 {@code welcome} 里这个用户的调用；还没 {@code welcome} 时一个都不接。 */
    @Test
    void aCallForAnotherUserIsRefused() throws Exception {
        Calls fresh = new Calls(dirs, true, false, journal, Tools.all());
        fresh.call(call("c-0", "alice", "read_file", "path", "a.txt"), replies::add);
        assertEquals(new Result("c-0", Calls.OTHER_USER, true, List.of()), reply());

        Calls calls = calls(true);
        calls.call(call("c-1", "mallory", "read_file", "path", "a.txt"), replies::add);
        assertEquals(new Result("c-1", Calls.OTHER_USER, true, List.of()), reply());
        assertTrue(printed().contains("read_file a.txt denied"), printed());
    }

    /** 需求 2.2：越界路径和不存在的文件给服务器的报错一样；终端里分得清：越界记 denied，不存在记 error。 */
    @Test
    void aPathOutsideTheAllowedDirectoriesIsRefused() throws Exception {
        Calls calls = calls(true);
        Files.createSymbolicLink(work.resolve("escape"), outside);
        calls.call(call("c-1", "alice", "read_file", "path", outside.resolve("b.txt").toString()), replies::add);
        Result absolute = reply();
        calls.call(call("c-2", "alice", "read_file", "path", "../outside/b.txt"), replies::add);
        Result dotdot = reply();
        calls.call(call("c-3", "alice", "read_file", "path", "escape/b.txt"), replies::add);
        Result symlink = reply();
        calls.call(call("c-4", "alice", "read_file", "path", "missing.txt"), replies::add);
        Result missing = reply();
        for (Result refused : List.of(absolute, dotdot, symlink, missing)) {
            assertTrue(refused.error());
            assertTrue(refused.text().startsWith(AllowedDirs.NOT_FOUND), refused.text());
            assertFalse(refused.text().contains("outside\n"));
        }
        List<String> lines = printed().lines().toList();
        assertTrue(lines.get(0).endsWith(" denied"), lines.get(0));
        assertTrue(lines.get(1).endsWith(" denied"), lines.get(1));
        assertTrue(lines.get(2).endsWith(" denied"), lines.get(2));
        assertTrue(lines.get(3).endsWith("missing.txt error"), lines.get(3));
    }

    /** L15：每个调用一行，时间、会话、工具、路径、结果；不打文件内容。 */
    @Test
    void theJournalHasOneLinePerCallAndNoContent() throws Exception {
        Calls calls = calls(true);
        calls.call(call("c-1", "alice", "read_file", "path", "a.txt"), replies::add);
        assertTrue(reply().text().contains("SECRET-CONTENT"));
        calls.call(call("c-2", "alice", "edit_file", "path", "a.txt", "old_string", "SECRET-CONTENT", "new_string", "OTHER-SECRET"),
                replies::add);
        assertFalse(reply().error());
        calls.call(call("c-3", "alice", "grep", "pattern", "OTHER-SECRET"), replies::add);
        reply();
        String expectedTime = java.time.LocalTime.now(NOON).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        assertEquals(List.of(expectedTime + " s-1a2b3c read_file a.txt ok", expectedTime + " s-1a2b3c edit_file a.txt ok",
                expectedTime + " s-1a2b3c grep . ok"), printed().lines().toList());
        assertFalse(printed().contains("SECRET"), printed());
    }

    /** 服务器给的参数里有控制字符时，终端里换成 {@code ?}，改不了用户的终端。 */
    @Test
    void controlCharactersDoNotReachTheTerminal() throws Exception {
        Calls calls = calls(true);
        calls.call(call("c-1", "alice", "read_file", "path", "a\u001b[2Jb‮.txt"), replies::add);
        reply();
        assertTrue(printed().contains("read_file a?[2Jb?.txt error"), printed());
        assertFalse(printed().contains("\u001b"));
    }

    @Test
    void anUnknownToolIsRefused() throws Exception {
        Calls calls = calls(true);
        calls.call(call("c-1", "alice", "rm_rf", "path", "."), replies::add);
        assertEquals(new Result("c-1", "Unknown tool: rm_rf", true, List.of()), reply());
    }

    /** L8：{@code cancel} 打断那一个调用，不再回结果；断开时打断全部。 */
    @Test
    void cancelAndDisconnectInterruptTheCalls() throws Exception {
        Blocking blocking = new Blocking();
        Calls calls = new Calls(dirs, true, false, journal, List.of(blocking));
        calls.welcome("alice");
        for (String id : List.of("c-1", "c-2", "c-3")) {
            calls.call(call(id, "alice", "block"), replies::add);
        }
        assertTrue(blocking.started.await(5, TimeUnit.SECONDS));
        assertEquals(3, calls.running());

        calls.cancel("c-2");
        assertEquals("c-2", blocking.interrupted.poll(5, TimeUnit.SECONDS));
        calls.stopAll();
        List<String> rest = new ArrayList<>(List.of(blocking.interrupted.poll(5, TimeUnit.SECONDS),
                blocking.interrupted.poll(5, TimeUnit.SECONDS)));
        rest.sort(null);
        assertEquals(List.of("c-1", "c-3"), rest);
        assertNull(replies.poll(300, TimeUnit.MILLISECONDS), "cancelled calls send no result");
        assertEquals(3, printed().lines().filter(line -> line.endsWith(" cancelled")).count(), printed());
        assertEquals(0, calls.running());
    }

    /** review-fixes 需求 11.10：同一个 id 还在跑时再来一次，回错误，不悄悄丢掉。 */
    @Test
    void aDuplicateCallIdIsRefusedWhileTheFirstRuns() throws Exception {
        Blocking blocking = new Blocking();
        Calls calls = new Calls(dirs, true, false, journal, List.of(blocking));
        calls.welcome("alice");
        calls.call(call("c-1", "alice", "block"), replies::add);
        for (int i = 0; i < 50 && calls.running() < 1; i++) {
            Thread.sleep(20);
        }
        assertEquals(1, calls.running());
        calls.call(call("c-1", "alice", "block"), replies::add);
        assertEquals(new Result("c-1", Calls.DUPLICATE, true, List.of()), reply());
        assertEquals(1, calls.running(), "the first call is still the only one");
        calls.stopAll();
        assertEquals("c-1", blocking.interrupted.poll(5, TimeUnit.SECONDS));
    }

    /** {@code AGENTS.md}：主目录里的那份；没有、或者是指向外面的符号链接时没有；超过 64 KB 截断并注明。 */
    @Test
    void contextReadsAgentsFromTheMainDirectory() throws Exception {
        Calls calls = calls(false);
        assertNull(calls.context(new Context("x-1")).agents());

        Files.writeString(work.resolve("AGENTS.md"), "  Use tabs.\n");
        assertEquals(new ContextResult("x-2", "Use tabs."), calls.context(new Context("x-2")));

        Files.delete(work.resolve("AGENTS.md"));
        Files.writeString(outside.resolve("AGENTS.md"), "steal");
        Files.createSymbolicLink(work.resolve("AGENTS.md"), outside.resolve("AGENTS.md"));
        assertNull(calls.context(new Context("x-3")).agents());

        Files.delete(work.resolve("AGENTS.md"));
        Files.writeString(work.resolve("AGENTS.md"), "a".repeat(ContextResult.MAX_AGENTS_BYTES - 1) + "é" + "tail");
        String capped = calls.context(new Context("x-4")).agents();
        assertTrue(capped.endsWith("\n\n(This file was truncated at 65536 bytes.)"), capped.substring(capped.length() - 60));
        assertFalse(capped.contains("tail"));
        assertFalse(capped.contains("�"), "cut on a UTF-8 boundary");
    }

    /**
     * 全部真工具都按类别检查（需求 2.3）：改文件的两个要 {@code --allow-write}，{@code bash} 要 {@code --allow-shell} 而且沙箱可用，
     * 其余的都不要。服务器硬发 {@code bash} 也被拒，命令不会跑。
     */
    @Test
    void onlyTheWriteToolsNeedAllowWriteAndOnlyBashNeedsTheShell() throws Exception {
        Calls calls = calls(false);
        int id = 0;
        for (LocalTool tool : Tools.all()) {
            calls.call(call("c-" + ++id, "alice", tool.name()), replies::add);
            Result result = reply();
            assertEquals(tool.kind() == LocalTool.Kind.WRITE, Calls.NO_WRITE.equals(result.text()), tool.name() + ": " + result.text());
            assertEquals(tool.kind() == LocalTool.Kind.SHELL, Calls.NO_SHELL.equals(result.text()), tool.name() + ": " + result.text());
        }
        assertTrue(Tools.all().stream().anyMatch(tool -> tool.kind() == LocalTool.Kind.SHELL), "bash is in the list");
    }

    /** {@code --allow-shell} 给了但沙箱不可用：照样拒绝，不会不隔离直接跑（L13）。 */
    @Test
    void anUnavailableSandboxRefusesBashEvenWithAllowShell() throws Exception {
        Calls calls = new Calls(dirs, true, true, tantalum.local.sandbox.Sandbox.unavailable(tantalum.local.sandbox.Unavailable.of(tantalum.local.sandbox.Unavailable.Code.MXC_MISSING)), journal, Tools.all());
        calls.welcome("alice");
        calls.call(call("b-1", "alice", "bash"), replies::add);
        assertEquals(Calls.NO_SHELL, reply().text());
    }

    /** 一直等到被打断的工具。 */
    private static final class Blocking implements LocalTool {

        final CountDownLatch started = new CountDownLatch(3);
        final BlockingQueue<String> interrupted = new LinkedBlockingQueue<>();

        @Override public String name() { return "block"; }
        @Override public String description() { return "blocks"; }
        @Override public Json schema() { return new Json.Obj(Map.of()); }
        @Override public Kind kind() { return Kind.READ; }

        @Override
        public LocalResult run(LocalContext context, Json arguments) throws InterruptedException {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException stopped) {
                interrupted.add(Thread.currentThread().getName().substring("call-".length()));
                throw stopped;
            }
            return LocalResult.ok("done");
        }
    }
}
