package tantalum.shell.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.access.PluginSwitches.Kind;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.PluginInfo;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.plugin.Workspace;
import tantalum.shell.tool.ToolCatalog;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.state.KernelState;
import tantalum.value.text.LocalText;

/** core-tools C13、C16 和需求 8 第 3 条。 */
class PluginGateTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");

    @TempDir
    Path root;

    private final AtomicInteger runs = new AtomicInteger();
    private final AtomicInteger afterRuns = new AtomicInteger();
    private final AtomicInteger workRuns = new AtomicInteger();
    private ToolCatalog catalog;
    private PluginSwitches switches;
    private PluginGate gate;

    private Tool tool(String name, String server) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Json schema() { return new Json.Obj(java.util.Map.of()); }
            @Override public String server() { return server; }
            @Override public ToolOutput run(ToolContext context, Json arguments) {
                runs.incrementAndGet();
                return ToolOutput.ok("ran " + name);
            }
        };
    }

    /** 一个插件：一个钩子、一个工作类型、一个工具后处理，plugin() 都是 citations。 */
    static final class CitationHook implements KernelHook, PluginInfo {
        @Override public String name() { return "citationCheck"; }
        @Override public String plugin() { return "citations"; }
        @Override public LocalText description() { return LocalText.of("checks citations"); }
        @Override public HookResult beforeRunEnd(KernelState state, HookContext context) { return Allow.PLAIN; }
    }

    static final class QuotaHook implements KernelHook, PluginInfo {
        @Override public String name() { return "quota"; }
        @Override public boolean mandatory() { return true; }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) { return Allow.PLAIN; }
    }

    private WorkType citationWork() {
        return new WorkType() {
            @Override public String name() { return "citation"; }
            @Override public String plugin() { return "citations"; }
            @Override public WorkResult run(ToolContext context, Json input) {
                workRuns.incrementAndGet();
                return new WorkResult(new Json.Str("ok"), null);
            }
        };
    }

    private AfterToolHook marker() {
        return new AfterToolHook() {
            @Override public String plugin() { return "citations"; }
            @Override public ToolOutput afterToolExecution(ToolContext context, String tool, Json arguments, ToolOutput output) {
                afterRuns.incrementAndGet();
                return ToolOutput.ok(output.text() + " +marked");
            }
        };
    }

    @BeforeEach
    void setUp() throws Exception {
        catalog = new ToolCatalog();
        catalog.register(tool("read_file", "workspace"));
        catalog.register(tool("write_file", "workspace"));
        // 和 McpTool 一样：server() 带前缀（并发名额的命名空间），开关按配置里的服务器名
        Tool snapshot = tool("browser__snapshot", "mcp:browser");
        catalog.setSource(new ToolCatalog.ToolSource() {
            @Override public List<Tool> tools() { return List.of(snapshot); }
            @Override public String serverName(Tool tool) { return "browser"; }
            @Override public Optional<Tool> find(String name) { return name.equals(snapshot.name()) ? Optional.of(snapshot) : Optional.empty(); }
        });
        switches = PluginSwitches.inMemory(Clock.systemUTC());
        gate = new PluginGate(catalog, switches, List.of("browser", "search"), List.of(new CitationHook(), new QuotaHook()),
                List.of(marker()), List.of(citationWork()));
    }

    private ToolContext context(Principal user) throws Exception {
        return new ToolContext(user, new SessionId("s"), new Workspace(root));
    }

    @Test
    void everythingIsEnabledByDefault() {
        assertEquals(List.of("browser__snapshot", "read_file", "write_file"), gate.enabledTools(ALICE));
        assertEquals(List.of("citations", "quota"), gate.enabledPlugins(ALICE));
    }

    @Test
    void mcpToolsAreListedUnderTheirConfiguredServer() {
        PluginGate.Overview overview = gate.overview(ALICE);
        assertEquals(List.of("browser", "search"), overview.mcp().stream().map(PluginGate.ServerView::name).toList());
        assertEquals(List.of("browser__snapshot"), overview.mcp().get(0).tools().stream().map(PluginGate.ToolView::name).toList());
        assertEquals(List.of("read_file", "write_file"), overview.tools().stream().map(PluginGate.ToolView::name).toList());
    }

    @Test
    void aToolCanBeTurnedOffForOneUser() {
        gate.set(ALICE, Kind.TOOL, "write_file", false);
        assertEquals(List.of("browser__snapshot", "read_file"), gate.enabledTools(ALICE));
        assertEquals(List.of("browser__snapshot", "read_file", "write_file"), gate.enabledTools(BOB));
    }

    /** MCP 服务器关掉等于关掉它的全部工具；内置工具不受服务器开关影响。 */
    @Test
    void turningOffAnMcpServerHidesItsTools() {
        gate.set(ALICE, Kind.MCP, "browser", false);
        assertEquals(List.of("read_file", "write_file"), gate.enabledTools(ALICE));
        switches.set(ALICE, Kind.MCP, "workspace", false);
        assertTrue(gate.enabledTools(ALICE).contains("read_file"));
    }

    /** 需求 8 第 3 条：关掉的工具在执行时立即拒绝，不执行。 */
    @Test
    void aTurnedOffToolIsRefusedAtExecution() throws Exception {
        Tool write = catalog.find("write_file").orElseThrow();
        assertEquals("ran write_file", gate.runTool(context(ALICE), write, new Json.Null()).text());
        gate.set(ALICE, Kind.TOOL, "write_file", false);
        ToolOutput refused = gate.runTool(context(ALICE), write, new Json.Null());
        assertTrue(refused.error());
        assertEquals(PluginGate.TOOL_OFF, refused.text());
        assertEquals(1, runs.get());
        assertEquals("ran write_file", gate.runTool(context(BOB), write, new Json.Null()).text());
    }

    @Test
    void aTurnedOffPluginsAfterToolHookAndWorkDoNotRun() throws Exception {
        ToolContext alice = context(ALICE);
        Tool read = catalog.find("read_file").orElseThrow();
        assertEquals("x +marked", gate.afterTool(alice, read, new Json.Null(), ToolOutput.ok("x")).text());
        gate.runWork(alice, citationWork(), new Json.Null());
        assertEquals(1, workRuns.get());

        gate.set(ALICE, Kind.PLUGIN, "citations", false);
        assertEquals("x", gate.afterTool(alice, read, new Json.Null(), ToolOutput.ok("x")).text());
        assertThrows(IllegalStateException.class, () -> gate.runWork(alice, citationWork(), new Json.Null()));
        assertEquals(1, afterRuns.get());
        assertEquals(1, workRuns.get());
        assertEquals(List.of("quota"), gate.enabledPlugins(ALICE));
    }

    /** C13：同一个插件的钩子、工作类型、工具后处理是一项，列出挂在哪些位置。 */
    @Test
    void pluginsAreGroupedByTheirPluginName() {
        assertEquals(List.of(
                new PluginGate.Plugin("citations", LocalText.of("checks citations"), List.of("beforeRunEnd", "afterToolExecution", "work:citation"), false, true),
                new PluginGate.Plugin("quota", LocalText.empty(), List.of("beforeModelCall"), true, true)), gate.plugins());
    }

    /** 策略插件默认关（2026-11-06 Yi 定，A2）：用户没设置过时不开，在插件页打开后才生效，只对他自己。 */
    static final class ReadOnlyPolicy implements KernelHook, PluginInfo {
        @Override public String name() { return "readOnly"; }
        @Override public boolean defaultEnabled() { return false; }
        @Override public HookResult beforeToolCall(KernelState state, HookContext context) { return Allow.PLAIN; }
    }

    @Test
    void aDefaultOffPluginStaysOffUntilTheUserTurnsItOn() {
        PluginGate policies = new PluginGate(catalog, switches, List.of(), List.of(), List.of(new ReadOnlyPolicy(), new CitationHook()),
                List.of(), List.of(), java.util.Set.of());
        assertEquals(List.of("citations"), policies.enabledPlugins(ALICE));
        PluginGate.PluginView view = policies.overview(ALICE).plugins().stream().filter(p -> p.name().equals("readOnly")).findFirst().orElseThrow();
        assertFalse(view.enabled());
        assertFalse(view.mandatory(), "default-off policy plugins are optional");

        policies.set(ALICE, Kind.PLUGIN, "readOnly", true);
        assertEquals(List.of("citations", "readOnly"), policies.enabledPlugins(ALICE));
        assertEquals(List.of("citations"), policies.enabledPlugins(BOB));
        policies.set(ALICE, Kind.PLUGIN, "readOnly", false);
        assertEquals(List.of("citations"), policies.enabledPlugins(ALICE));
    }

    /** 管理员用 tantalum.hooks.required 强制开启（A2）：默认关的插件对所有人开着，不能关；拼错的名字拒绝启动。 */
    @Test
    void anAdminCanRequireADefaultOffPlugin() {
        PluginGate required = new PluginGate(catalog, switches, List.of(), List.of(), List.of(new ReadOnlyPolicy()),
                List.of(), List.of(), java.util.Set.of("readOnly"));
        assertEquals(List.of("readOnly"), required.enabledPlugins(BOB));
        assertTrue(required.plugins().getFirst().mandatory());
        assertThrows(IllegalArgumentException.class, () -> required.set(ALICE, Kind.PLUGIN, "readOnly", false));

        IllegalArgumentException typo = assertThrows(IllegalArgumentException.class, () -> new PluginGate(catalog, switches,
                List.of(), List.of(), List.of(new ReadOnlyPolicy()), List.of(), List.of(), java.util.Set.of("readonly")));
        assertTrue(typo.getMessage().contains("readonly"), typo.getMessage());
    }

    /** 策略类钩子（mandatory）的插件不能关，否则用户自己就能绕过额度。 */
    @Test
    void aMandatoryPluginCannotBeTurnedOff() {
        assertThrows(IllegalArgumentException.class, () -> gate.set(ALICE, Kind.PLUGIN, "quota", false));
        switches.set(ALICE, Kind.PLUGIN, "quota", false);
        assertTrue(gate.pluginEnabled(ALICE, "quota"), "a stale switch row does not turn it off either");
        gate.set(ALICE, Kind.PLUGIN, "quota", true);
    }

    @Test
    void unknownNamesAreRejected() {
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.set(ALICE, Kind.TOOL, "nope", false));
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.set(ALICE, Kind.MCP, "nope", false));
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.set(ALICE, Kind.PLUGIN, "nope", false));
        // 配置了但还没连上的服务器可以先关掉
        gate.set(ALICE, Kind.MCP, "search", false);
        assertFalse(switches.enabled(ALICE, Kind.MCP, "search"));
    }

    /** 不在名单上的插件（不该出现）一律不执行。 */
    @Test
    void workOfAnUnknownPluginIsRefused() throws Exception {
        WorkType stray = new WorkType() {
            @Override public String name() { return "stray"; }
            @Override public WorkResult run(ToolContext context, Json input) { return new WorkResult(new Json.Null(), null); }
        };
        assertThrows(IllegalStateException.class, () -> gate.runWork(context(ALICE), stray, new Json.Null()));
    }

    /** review-fixes 需求 12.7：钩子位置的名字从枚举派生，onAnswer 也在；工具后处理 fail-closed 时出错让这次调用以错误结束。 */
    @Test
    void hookPointsComeFromTheEnumAndFailClosedAfterToolHooksTurnFailuresIntoErrors() throws Exception {
        assertEquals(List.of("beforeModelCall", "beforeToolCall", "onFailure", "beforeRunEnd", "onWorkResult", "beforeCompact", "onAnswer"),
                PluginGate.HOOK_POINTS);
        KernelHook answering = new KernelHook() {
            @Override public String name() { return "answers"; }
            @Override public HookResult onAnswer(KernelState state, HookContext context) { return Allow.PLAIN; }
        };
        AfterToolHook strict = new AfterToolHook() {
            @Override public String plugin() { return "strict"; }
            @Override public boolean failClosed() { return true; }
            @Override public ToolOutput afterToolExecution(ToolContext context, String tool, Json arguments, ToolOutput output) {
                throw new IllegalStateException("/secret/path");
            }
        };
        AfterToolHook lax = new AfterToolHook() {
            @Override public String plugin() { return "lax"; }
            @Override public ToolOutput afterToolExecution(ToolContext context, String tool, Json arguments, ToolOutput output) {
                throw new IllegalStateException("boom");
            }
        };
        PluginGate strictGate = new PluginGate(catalog, switches, List.of(), List.of(answering), List.of(lax, strict, marker()), List.of());
        assertEquals(List.of("onAnswer"), strictGate.overview(ALICE).plugins().stream()
                .filter(p -> p.name().equals("answers")).findFirst().orElseThrow().points());
        ToolOutput output = strictGate.afterTool(context(ALICE), tool("read_file", "workspace"), new Json.Obj(java.util.Map.of()), ToolOutput.ok("text"));
        assertTrue(output.error());
        assertTrue(output.text().contains("strict") && output.text().contains("IllegalStateException"), output.text());
        assertFalse(output.text().contains("/secret/path"), "only the class name reaches the model");
        assertEquals(0, afterRuns.get(), "the hook after the failing one did not run");

        PluginGate laxGate = new PluginGate(catalog, switches, List.of(), List.of(), List.of(lax, marker()), List.of());
        ToolOutput kept = laxGate.afterTool(context(ALICE), tool("read_file", "workspace"), new Json.Obj(java.util.Map.of()), ToolOutput.ok("text"));
        assertEquals("text +marked", kept.text(), "a lax hook's failure keeps the previous output and the rest run");
    }
}
