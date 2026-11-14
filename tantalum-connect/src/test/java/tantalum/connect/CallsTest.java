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
import tantalum.local.wire.Browse;
import tantalum.local.wire.BrowseResult;
import tantalum.local.wire.Call;
import tantalum.local.wire.Context;
import tantalum.local.wire.ContextResult;
import tantalum.local.wire.DirState;
import tantalum.local.wire.Result;
import tantalum.local.wire.SkillList;
import tantalum.local.wire.SkillLoad;
import tantalum.local.wire.SkillLoaded;
import tantalum.local.wire.WikiList;
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

    /**
     * QAgent 问题 72：Playwright MCP 自己的工具（如 {@code browser_run_code_unsafe}）不能被服务器直接调用，只能经 {@code browser_*}。
     * 以前 {@code mcp:playwright:*} 单独拒绝；remove-generic-mcp 后连接程序根本不认 {@code mcp:} 开头的调用。
     */
    @Test
    void mcpCallsAreUnknownTools() throws Exception {
        Calls calls = new Calls(dirs, true, false, journal, Tools.all());
        calls.welcome("alice");
        calls.call(call("m-1", "alice", "mcp:playwright:browser_run_code_unsafe"), replies::add);
        assertEquals("Unknown tool: mcp:playwright:browser_run_code_unsafe", reply().text());
        calls.call(call("m-2", "alice", "mcp:other:x"), replies::add);
        assertEquals("Unknown tool: mcp:other:x", reply().text());
    }

    /** {@code --allow-shell} 给了但电脑上找不到 shell：照样拒绝。 */
    @Test
    void aMissingShellRefusesBashEvenWithAllowShell() throws Exception {
        Calls calls = new Calls(dirs, true, true, tantalum.local.shell.Shell.unavailable(tantalum.local.shell.Unavailable.of(
                tantalum.local.shell.Unavailable.Code.NOT_FOUND, "shell", "bash")), journal, Tools.all());
        calls.welcome("alice");
        calls.call(call("b-1", "alice", "bash"), replies::add);
        assertEquals(Calls.NO_SHELL, reply().text());
    }

    /** connector-jar：服务器按 hello 只给一种 shell 的工具；另一种的调用（mac 上来了 powershell）在电脑上也拒。 */
    @Test
    void theOtherShellsToolIsRefused() throws Exception {
        tantalum.local.shell.Shell bash = new tantalum.local.shell.Shell() {
            @Override public Status check() { return Status.ok(); }
            @Override public tantalum.local.shell.ShellKind kind() { return tantalum.local.shell.ShellKind.BASH; }
            @Override public Run run(String command, Path workdir, java.time.Duration timeout) { throw new AssertionError("not run"); }
        };
        Calls calls = new Calls(dirs, true, true, bash, journal, Tools.all());
        calls.welcome("alice");
        calls.call(call("p-1", "alice", "powershell"), replies::add);
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

    // ---- 会话目录和列子目录（workspaces 任务 14，W5、W6） ----

    private Call inDir(String id, String dir, String tool, String path) {
        return new Call(id, "alice", "s-1a2b3c4d5e", tool, new Json.Obj(Map.of("path", new Json.Str(path))), 60_000, dir);
    }

    @Test
    void aCallWithASessionDirectoryReadsRelativePathsFromThere() throws Exception {
        Path app = Files.createDirectory(work.resolve("app"));
        Files.writeString(app.resolve("a.txt"), "from app\n");
        Calls calls = calls(false);
        calls.call(inDir("c-1", app.toString(), "read_file", "a.txt"), replies::add);
        Result fromApp = reply();
        assertFalse(fromApp.error(), fromApp.text());
        assertTrue(fromApp.text().contains("from app"), fromApp.text());
        calls.call(inDir("c-2", null, "read_file", "a.txt"), replies::add);
        assertTrue(reply().text().contains("SECRET-CONTENT"), "without a directory the main directory is the start");
    }

    @Test
    void aSessionDirectoryOutsideTheAllowedOnesRunsNothing() throws Exception {
        Calls calls = calls(true);
        calls.call(inDir("c-1", outside.toString(), "write_file", "x.txt"), replies::add);
        Result refused = reply();
        assertTrue(refused.error());
        assertEquals(Calls.DIR_NOT_ALLOWED + outside, refused.text());
        assertFalse(Files.exists(outside.resolve("x.txt")));
        assertFalse(Files.exists(work.resolve("x.txt")), "it does not fall back to the main directory");
        calls.call(inDir("c-2", work.resolve("missing").toString(), "read_file", "a.txt"), replies::add);
        assertTrue(reply().text().startsWith(Calls.DIR_NOT_ALLOWED));
        assertTrue(printed().contains(" denied"), printed());
    }

    @Test
    void contextReadsAgentsFromTheSessionDirectory() throws Exception {
        Path app = Files.createDirectory(work.resolve("app"));
        Files.writeString(work.resolve("AGENTS.md"), "main rules");
        Files.writeString(app.resolve("AGENTS.md"), "app rules");
        Calls calls = calls(false);
        assertEquals("app rules", calls.context(new Context("x-1", app.toString())).agents());
        assertEquals("main rules", calls.context(new Context("x-2")).agents());
        assertNull(calls.context(new Context("x-3", outside.toString())).agents());
    }

    @Test
    void browsingListsOnlyAllowedFoldersSortedWithoutHiddenOnesOrLinks() throws Exception {
        Files.createDirectory(work.resolve("zeta"));
        Files.createDirectory(work.resolve("Alpha"));
        Files.createDirectory(work.resolve(".git"));
        Files.createSymbolicLink(work.resolve("escape"), outside);
        Calls calls = calls(false);

        BrowseResult roots = calls.browse(new Browse("b-0", null));
        assertEquals(List.of(work.toString()), roots.dirs());

        BrowseResult listed = calls.browse(new Browse("b-1", work.toString()));
        assertEquals(work.toString(), listed.path());
        assertEquals(List.of(work.resolve("Alpha").toString(), work.resolve("zeta").toString()), listed.dirs());
        assertFalse(listed.truncated());
        assertNull(listed.error());

        BrowseResult refused = calls.browse(new Browse("b-2", outside.toString()));
        BrowseResult missing = calls.browse(new Browse("b-3", work.resolve("nope").toString()));
        assertEquals(AllowedDirs.NOT_FOUND + outside, refused.error());
        assertEquals(AllowedDirs.NOT_FOUND + work.resolve("nope"), missing.error());
        assertEquals(List.of(), refused.dirs());
        assertEquals("", printed(), "listing folders is not a tool call and is not journaled");
    }

    @Test
    void browsingStopsAtTheLimit() throws Exception {
        for (int i = 0; i <= BrowseResult.MAX_DIRS; i++) {
            Files.createDirectory(work.resolve(String.format("d%04d", i)));
        }
        BrowseResult listed = calls(false).browse(new Browse("b-1", work.toString()));
        assertEquals(BrowseResult.MAX_DIRS, listed.dirs().size());
        assertTrue(listed.truncated());
    }

    // ---- skills-wiki：技能和 wiki ----

    private Path skill(String folder, String text) throws Exception {
        Path dir = Files.createDirectories(work.resolve("skills").resolve(folder));
        Files.writeString(dir.resolve("SKILL.md"), text);
        return dir;
    }

    /** 需求 2.2、6.1、9.5：一次 context 带回 AGENTS.md、技能清单、wiki 页；终端各记一行，只有目录和个数，没有内容。 */
    @Test
    void contextBringsBackSkillsAndWikiPages() throws Exception {
        Files.writeString(work.resolve("AGENTS.md"), "Use tabs.");
        skill("pdf-tools", "---\ndescription: SECRET-DESCRIPTION\n---\nSECRET-BODY");
        skill("broken", "nothing");
        Path wiki = Files.createDirectory(work.resolve("wiki"));
        Files.writeString(wiki.resolve("index.md"), "SECRET-INDEX");
        Files.writeString(wiki.resolve("setup.md"), "# SECRET-TITLE");
        String skills = work.resolve("skills").toString();

        ContextResult result = calls(false).context(new Context("x-1", null, skills, wiki.toString()));

        assertEquals("Use tabs.", result.agents());
        assertEquals(new SkillList(skills, DirState.OK, List.of(new SkillList.Item("pdf-tools", "SECRET-DESCRIPTION", true, true)),
                List.of(new SkillList.Problem("broken", "description-missing", null))), result.skills());
        assertEquals(new WikiList(wiki.toString(), DirState.OK, List.of(new WikiList.Page("setup.md", "SECRET-TITLE")), 1, "SECRET-INDEX"),
                result.wiki());
        assertEquals("03:02:13 - skills " + skills + " ok 1\n03:02:13 - wiki " + wiki + " ok 1\n", printed().replace("\r\n", "\n"));
    }

    /** 没问就不带：协议 3 的服务器发来的 context 得到的还是原来那样，终端也不多记。 */
    @Test
    void contextWithoutTheNewFieldsAnswersAsBefore() throws Exception {
        skill("pdf-tools", "---\ndescription: D\n---\nx");
        ContextResult result = calls(false).context(new Context("x-1"));
        assertNull(result.skills());
        assertNull(result.wiki());
        assertEquals("", printed());
    }

    /** 需求 9.1：服务器让列允许目录外的目录，答“不在范围里”，什么都不读。 */
    @Test
    void contextRefusesDirectoriesOutsideTheAllowedOnes() throws Exception {
        Files.writeString(Files.createDirectory(outside.resolve("leak")).resolve("SKILL.md"), "---\ndescription: LEAK\n---\nx");
        Files.writeString(outside.resolve("page.md"), "# LEAK");
        ContextResult result = calls(false).context(new Context("x-1", null, outside.toString(), outside.toString()));
        assertEquals(SkillList.unusable(DirState.OUTSIDE), result.skills());
        assertEquals(WikiList.unusable(DirState.OUTSIDE), result.wiki());
        assertTrue(printed().contains("- skills " + outside + " outside"), printed());
        assertTrue(printed().contains("- wiki " + outside + " outside"), printed());
    }

    /** 需求 4：skill 是只读的一类，没有 --allow-write 也能用；调用里带着技能目录；终端记名字，不记正文。 */
    @Test
    void theSkillToolLoadsFromTheDirectoryInTheCall() throws Exception {
        skill("pdf-tools", "---\ndescription: D\n---\nSECRET-BODY");
        Calls calls = calls(false);
        Json name = new Json.Obj(Map.of("name", new Json.Str("pdf-tools")));
        calls.call(new Call("c-1", "alice", "s-1a2b3c4d5e", "skill", name, 60_000, null, work.resolve("skills").toString()), replies::add);
        Result loaded = reply();
        assertFalse(loaded.error(), loaded.text());
        assertTrue(loaded.text().contains("<skill_instructions>\nSECRET-BODY\n</skill_instructions>"), loaded.text());

        calls.call(new Call("c-2", "alice", "s-1a2b3c4d5e", "skill", name, 60_000), replies::add);
        assertEquals(new Result("c-2", "Error: skill \"pdf-tools\" is unknown or no longer available", true, List.of()), reply());

        assertTrue(printed().contains("skill pdf-tools ok"), printed());
        assertTrue(printed().contains("skill pdf-tools error"), printed());
        assertFalse(printed().contains("SECRET"), printed());
    }

    /** 需求 5：用户点名的技能当场读；没有的、不让点名的两样都不带；读坏了的带原因。 */
    @Test
    void skillLoadReadsWhatTheUserNamed() throws Exception {
        skill("pdf-tools", "---\ndescription: D\n---\nSECRET-BODY");
        skill("manual", "---\ndescription: D\ndisable-model-invocation: true\n---\nManual steps");
        skill("background", "---\ndescription: D\nuser-invocable: false\n---\nx");
        skill("broken", "---\nname: broken\n---\nx");
        String skills = work.resolve("skills").toString();

        SkillLoaded loaded = calls(false).skillLoad(new SkillLoad("k-1", "s-1a2b3c4d5e", skills,
                List.of("pdf-tools", "manual", "background", "broken", "nope", "Not A Name")));

        assertEquals("k-1", loaded.id());
        assertEquals(List.of("pdf-tools", "manual", "background", "broken", "nope", "Not A Name"),
                loaded.items().stream().map(SkillLoaded.Loaded::name).toList());
        assertTrue(loaded.items().get(0).content().contains("SECRET-BODY"));
        assertTrue(loaded.items().get(1).content().contains("Manual steps"));
        assertEquals(new SkillLoaded.Loaded("background", null, null), loaded.items().get(2));
        assertEquals(new SkillLoaded.Loaded("broken", null, "description-missing"), loaded.items().get(3));
        assertEquals(new SkillLoaded.Loaded("nope", null, null), loaded.items().get(4));
        assertEquals(new SkillLoaded.Loaded("Not A Name", null, null), loaded.items().get(5));
        assertEquals("03:02:13 s-1a2b3c skill pdf-tools ok\n03:02:13 s-1a2b3c skill manual ok\n03:02:13 s-1a2b3c skill broken error\n",
                printed().replace("\r\n", "\n"));
    }

    @Test
    void skillLoadFromOutsideTheAllowedDirectoriesLoadsNothing() throws Exception {
        Files.writeString(Files.createDirectory(outside.resolve("leak")).resolve("SKILL.md"), "---\ndescription: D\n---\nLEAK");
        SkillLoaded loaded = calls(false).skillLoad(new SkillLoad("k-1", "s-1", outside.toString(), List.of("leak")));
        assertEquals(List.of(new SkillLoaded.Loaded("leak", null, null)), loaded.items());
    }

    /** local-browser 需求 1.1、9.1：没有 --allow-browser，服务器硬发浏览器调用也被拒，终端记 denied 和网址。 */
    @Test
    void aBrowserCallWithoutAllowBrowserIsRefused() throws Exception {
        Calls calls = calls(true);
        calls.call(call("c-1", "alice", "browser_navigate", "url", "https://board.example/"), replies::add);
        assertEquals(new Result("c-1", "This computer does not allow the browser.", true, List.of()), reply());
        assertTrue(printed().contains("browser_navigate https://board.example/ denied"), printed());
    }

    /**
     * 需求 9.1–9.4：有浏览器时调用交给它；终端每个调用一行，对象是网址、元素编号、键名、表达式的开头；
     * 输入的文字、选的值、页面内容都不打出来。
     */
    @Test
    void browserCallsPrintWhatWasTouchedButNeverWhatWasTyped() throws Exception {
        List<String> seen = new ArrayList<>();
        Calls calls = new Calls(dirs, false, false, tantalum.local.shell.Shell.unavailable(
                tantalum.local.shell.Unavailable.of(tantalum.local.shell.Unavailable.Code.SHELL_NOT_ALLOWED)), journal,
                (session, tool, arguments) -> {
                    seen.add(session + " " + tool);
                    return LocalResult.ok("External web content follows.\nPAGE-CONTENT account 4242");
                });
        calls.welcome("alice");
        calls.call(call("c-1", "alice", "browser_navigate", "url", "https://board.example/login?next=%2F"), replies::add);
        assertEquals("External web content follows.\nPAGE-CONTENT account 4242", reply().text());
        calls.call(call("c-2", "alice", "browser_type", "ref", "e7", "text", "hunter2-PASSWORD"), replies::add);
        reply();
        calls.call(new Call("c-3", "alice", "s-1a2b3c4d5e", "browser_select_option", new Json.Obj(Map.of("ref", new Json.Str("e9"),
                "values", new Json.Arr(List.of(new Json.Str("SECRET-OPTION"))))), 60_000), replies::add);
        reply();
        calls.call(call("c-4", "alice", "browser_press_key", "key", "Enter"), replies::add);
        reply();
        calls.call(call("c-5", "alice", "browser_evaluate", "expression", "document.title"), replies::add);
        reply();
        calls.call(call("c-6", "alice", "browser_snapshot"), replies::add);
        reply();
        assertEquals(6, seen.size());
        assertEquals("s-1a2b3c4d5e browser_navigate", seen.getFirst());
        String printed = printed();
        assertTrue(printed.contains("03:02:13 s-1a2b3c browser_navigate https://board.example/login?next=%2F ok"), printed);
        assertTrue(printed.contains("browser_type e7 ok"), printed);
        assertTrue(printed.contains("browser_select_option e9 ok"), printed);
        assertTrue(printed.contains("browser_press_key Enter ok"), printed);
        assertTrue(printed.contains("browser_evaluate document.title ok"), printed);
        assertTrue(printed.contains("browser_snapshot . ok"), printed);
        assertFalse(printed.contains("hunter2"), printed);
        assertFalse(printed.contains("SECRET-OPTION"), printed);
        assertFalse(printed.contains("PAGE-CONTENT"), printed);
    }

    /** 需求 9.3：网址和表达式里的控制字符换掉、截到 160 个字符，改不了用户的终端。 */
    @Test
    void longOrOddBrowserSubjectsAreMadeSafeForTheTerminal() throws Exception {
        Calls calls = new Calls(dirs, false, false, tantalum.local.shell.Shell.unavailable(
                tantalum.local.shell.Unavailable.of(tantalum.local.shell.Unavailable.Code.SHELL_NOT_ALLOWED)), journal,
                (session, tool, arguments) -> LocalResult.ok("ok"));
        calls.welcome("alice");
        calls.call(call("c-1", "alice", "browser_evaluate", "expression", "a\u001b[2Jb\n" + "x".repeat(400)), replies::add);
        reply();
        String line = printed().lines().filter(text -> text.contains("browser_evaluate")).findFirst().orElseThrow();
        assertFalse(line.contains("\u001b"), line);
        assertTrue(line.contains("a?[2Jb?"), line);
        assertTrue(line.contains("…"), line);
        assertTrue(line.length() < 260, line);
    }
}
