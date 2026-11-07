package tantalum.shell.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.wire.Hello;
import tantalum.local.wire.Wire;
import tantalum.shell.access.PluginGate;
import tantalum.shell.access.PluginSwitches;
import tantalum.shell.access.PluginSwitches.Kind;
import tantalum.shell.memory.MemoryLimits;
import tantalum.shell.memory.MemoryStores;
import tantalum.shell.memory.MemoryTools;
import tantalum.shell.tool.BuiltinTools;
import tantalum.shell.tool.ToolCatalog;
import tantalum.shell.tool.Workspaces;
import tantalum.value.id.Principal;

/**
 * 本机工作区的开关和可用性（local-workspace 任务 14，L9、L17，需求 6.1、6.2）：总开关 × 子开关 × 电脑连着 × 连接程序允许。
 * 读附件和记忆工具不受影响。
 */
class LocalWorkspaceTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final List<String> READ = List.of("find_files", "grep", "list_dir", "read_file");
    private static final List<String> GIT = List.of("git_blame", "git_branches", "git_diff", "git_log", "git_show");
    private static final List<String> WRITE = List.of("edit_file", "write_file");
    private static final List<String> SERVER = List.of("ask_user", "memory_delete", "memory_read", "memory_write", "read_attachment");

    @TempDir
    Path temp;

    private LocalTokens tokens;
    private LocalLinks links;
    private PluginSwitches switches;
    private PluginGate gate;
    private Path mac;

    @BeforeEach
    void setUp() throws Exception {
        tokens = LocalTokens.open(temp.resolve("data"), Clock.systemUTC());
        links = new LocalLinks(tokens, Clock.systemUTC(), LocalLinks.Timing.DEFAULT);
        switches = PluginSwitches.inMemory(Clock.systemUTC());
        ToolCatalog catalog = new ToolCatalog();
        BuiltinTools.of(null).forEach(catalog::register);
        LocalTool.all(links, null, Duration.ofSeconds(60)).forEach(catalog::register);
        Workspaces workspaces = Workspaces.perUser(Files.createDirectories(temp.resolve("ws")));
        MemoryTools.all(new MemoryStores(workspaces, temp.resolve("data"), new MemoryLimits(2, 16 * 1024, 64), Clock.systemUTC()))
                .forEach(catalog::register);
        gate = new PluginGate(catalog, switches, List.of(new LocalWorkspace(switches, links)), List.of(), List.of(), List.of(), List.of(), java.util.Set.of());
        mac = Files.createDirectories(temp.resolve("mac"));
    }

    @AfterEach
    void tearDown() throws Exception {
        links.close();
        tokens.close();
        switches.close();
    }

    private TestComputer connect(boolean write, boolean git) throws Exception {
        return TestComputer.connect(links, ALICE, Instant.now().plus(Duration.ofHours(1)), mac, write, git);
    }

    private static List<String> sorted(List<String>... groups) {
        return java.util.stream.Stream.of(groups).flatMap(List::stream).sorted().toList();
    }

    @Test
    void withoutAComputerOnlyServerToolsAreOn() {
        assertEquals(SERVER, gate.enabledTools(ALICE));
    }

    @Test
    void whatTheComputerAllowsDecidesTheTools() throws Exception {
        try (TestComputer ignored = connect(false, false)) {
            assertEquals(sorted(READ, SERVER), gate.enabledTools(ALICE), "no --allow-write, not a git repository");
        }
        try (TestComputer ignored = connect(true, true)) {
            assertEquals(sorted(READ, GIT, WRITE, SERVER), gate.enabledTools(ALICE));
            assertEquals(SERVER, gate.enabledTools(BOB), "someone else's computer gives bob nothing");
        }
        assertEquals(SERVER, gate.enabledTools(ALICE), "disconnected");
    }

    @Test
    void switchesTurnToolsOffEvenWhenTheComputerAllowsThem() throws Exception {
        try (TestComputer ignored = connect(true, true)) {
            gate.set(ALICE, Kind.LOCAL, "file-edit", false);
            assertEquals(sorted(READ, GIT, SERVER), gate.enabledTools(ALICE));
            gate.set(ALICE, Kind.LOCAL, "git", false);
            gate.set(ALICE, Kind.LOCAL, "file-edit", true);
            assertEquals(sorted(READ, WRITE, SERVER), gate.enabledTools(ALICE));
            gate.set(ALICE, Kind.LOCAL, "fs-read", false);
            assertEquals(sorted(WRITE, SERVER), gate.enabledTools(ALICE));

            // 总开关关着时一个也没有，子开关的状态保留
            gate.set(ALICE, Kind.LOCAL, "fs-read", true);
            gate.set(ALICE, Kind.LOCAL, "local-workspace", false);
            assertEquals(SERVER, gate.enabledTools(ALICE));
            gate.set(ALICE, Kind.LOCAL, "local-workspace", true);
            assertEquals(sorted(READ, WRITE, SERVER), gate.enabledTools(ALICE));
        }
        // 开关按用户分别记（C16）：alice 关过的，bob 照样开着
        gate.set(ALICE, Kind.LOCAL, "fs-read", false);
        try (TestComputer ignored = TestComputer.connect(links, BOB, Instant.now().plus(Duration.ofHours(1)), mac, false, false)) {
            assertEquals(sorted(READ, SERVER), gate.enabledTools(BOB));
        }
    }

    /** 本机工作区的工具只用 LOCAL 开关，没有单独的工具开关，也不列在内置工具里。 */
    @Test
    void localToolsHaveNoSwitchOfTheirOwn() {
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.set(ALICE, Kind.TOOL, "read_file", false));
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.set(ALICE, Kind.LOCAL, "bash", false));
        gate.set(ALICE, Kind.TOOL, "read_attachment", false);
        assertFalse(gate.enabledTools(ALICE).contains("read_attachment"));
        assertEquals(SERVER, gate.overview(ALICE).tools().stream().map(PluginGate.ToolView::name).sorted().toList());
    }

    @Test
    void theConnectorDecidesWhatEachKindNeeds() {
        Hello hello = new Hello(Wire.PROTOCOL, "t", new Hello.Device("m", "macos", "26.2", "arm64"),
                List.of(new Hello.Root("/w"), new Hello.Root("/x")), false, new Hello.Shell(false, "no"), true, true);
        assertTrue(LocalWorkspace.allows(hello, tantalum.local.tool.LocalTool.Kind.READ));
        assertFalse(LocalWorkspace.allows(hello, tantalum.local.tool.LocalTool.Kind.WRITE));
        assertFalse(LocalWorkspace.allows(hello, tantalum.local.tool.LocalTool.Kind.SHELL));
        // B1：git 装了就有 Git 工具，不看主目录是不是仓库（每次调用在电脑上按路径找仓库）
        assertTrue(LocalWorkspace.allows(hello, tantalum.local.tool.LocalTool.Kind.GIT));
        Hello noGit = new Hello(Wire.PROTOCOL, "t", hello.device(), hello.roots(), false, hello.shell(), true, false);
        assertFalse(LocalWorkspace.allows(noGit, tantalum.local.tool.LocalTool.Kind.GIT));
    }

    @Test
    void switchStatesAreListedInOrder() {
        LocalWorkspace workspace = new LocalWorkspace(switches, links);
        switches.set(ALICE, Kind.LOCAL, "shell", false);
        assertEquals(Map.of("local-workspace", true, "fs-read", true, "git", true, "file-edit", true, "shell", false),
                workspace.switches(ALICE));
        assertEquals(List.of("local-workspace", "fs-read", "git", "file-edit", "shell"), List.copyOf(workspace.switches(ALICE).keySet()));
        assertEquals(Optional.empty(), workspace.section(ALICE));
    }
}
