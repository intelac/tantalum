package tantalum.shell.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.access.PluginGate.ForbiddenParam;
import tantalum.shell.access.PluginGate.Lock;
import tantalum.shell.access.PluginGate.LockedPlugin;
import tantalum.shell.access.PluginGate.ParamView;
import tantalum.shell.param.ParamRules.InvalidParam;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Configurable;
import tantalum.shell.plugin.DailyLimit;
import tantalum.shell.plugin.Kind;
import tantalum.shell.plugin.Param;
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
import tantalum.value.hook.Params;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.state.KernelState;

/** plugin-settings 阶段 B：参数的声明、锁、送达、保存和页面视图都经过 {@link PluginGate}。 */
class PluginParamsTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");

    private static final Param MAX_CHARS = Param.number("maxChars", 500).range(100, 10000);
    private static final Param TOKENS = Param.number("tokens", 1000).atLeast(1).adminOnly();
    private static final Param TOOLS = Param.list("tools", List.of("write_file"));
    private static final Param API_KEY = Param.secret("apiKey");

    @TempDir
    Path root;

    /** 每次调用拿到的上下文，测试看里面的参数和密码。 */
    private final List<ToolContext> seen = new ArrayList<>();
    private ToolCatalog catalog;
    private PluginSwitches switches;
    private PluginGate gate;
    private OptionalLong guardLimit = OptionalLong.empty();

    private static Json num(long value) {
        return new Json.Num(BigDecimal.valueOf(value));
    }

    /** 内置工具 search：一个普通参数、一个密码。 */
    private class SearchTool implements Tool, Configurable {
        @Override public String name() { return "search"; }
        @Override public String description() { return "search"; }
        @Override public Json schema() { return new Json.Obj(Map.of()); }
        @Override public String server() { return "workspace"; }
        @Override public List<Param> params() { return List.of(MAX_CHARS, API_KEY); }
        @Override public ToolOutput run(ToolContext context, Json arguments) {
            seen.add(context);
            return ToolOutput.ok("ok");
        }
    }

    private final class PlainTool implements Tool {
        private final String name;

        PlainTool(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public String description() { return name; }
        @Override public Json schema() { return new Json.Obj(Map.of()); }
        @Override public String server() { return "workspace"; }
        @Override public ToolOutput run(ToolContext context, Json arguments) {
            seen.add(context);
            return ToolOutput.ok("ok");
        }
    }

    /** 插件 quota：钩子声明 tokens（归管理员），默认关。 */
    static final class QuotaHook implements KernelHook, PluginInfo, Configurable, DailyLimit {
        @Override public OptionalLong dailyTokens(Params params) { return OptionalLong.of(params.number("tokens", -1)); }
        @Override public java.util.Optional<String> limitParam() { return java.util.Optional.of("tokens"); }
        @Override public String name() { return "quotaHook"; }
        @Override public String plugin() { return "quota"; }
        @Override public boolean defaultEnabled() { return false; }
        @Override public List<Param> params() { return List.of(TOKENS); }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) { return Allow.PLAIN; }
    }

    /** 插件 guard：钩子声明 tools，后处理再声明一遍 tools（一样）和 maxChars。 */
    static final class GuardHook implements KernelHook, Configurable {
        @Override public String name() { return "guardHook"; }
        @Override public String plugin() { return "guard"; }
        @Override public List<Param> params() { return List.of(TOOLS); }
        @Override public HookResult beforeToolCall(KernelState state, HookContext context) { return Allow.PLAIN; }
    }

    private final class GuardAfter implements AfterToolHook, Configurable {
        @Override public String plugin() { return "guard"; }
        @Override public List<Param> params() { return List.of(TOOLS, MAX_CHARS); }
        @Override public ToolOutput afterToolExecution(ToolContext context, String tool, Json arguments, ToolOutput output) {
            seen.add(context);
            return output;
        }
    }

    /** 插件 trim：只有后处理，带一个密码。 */
    private final class TrimAfter implements AfterToolHook, Configurable {
        @Override public String plugin() { return "trim"; }
        @Override public List<Param> params() { return List.of(MAX_CHARS, API_KEY); }
        @Override public ToolOutput afterToolExecution(ToolContext context, String tool, Json arguments, ToolOutput output) {
            seen.add(context);
            return output;
        }
    }

    private final class GuardWork implements WorkType, DailyLimit {
        // guard 默认开着：上限 800，或者不限
        @Override public OptionalLong dailyTokens(Params params) { return guardLimit; }
        @Override public String name() { return "audit"; }
        @Override public String plugin() { return "guard"; }
        @Override public WorkResult run(ToolContext context, Json input) {
            seen.add(context);
            return new WorkResult(new Json.Str("ok"), null);
        }
    }

    /** 必须开启的插件（代码里定的），没有参数。 */
    static final class AuditHook implements KernelHook, PluginInfo {
        @Override public String name() { return "auditHook"; }
        @Override public String plugin() { return "audit"; }
        @Override public boolean mandatory() { return true; }
        @Override public HookResult beforeRunEnd(KernelState state, HookContext context) { return Allow.PLAIN; }
    }

    @BeforeEach
    void setUp() throws Exception {
        catalog = new ToolCatalog();
        catalog.register(new SearchTool());
        catalog.register(new PlainTool("read_file"));
        switches = PluginSwitches.inMemory(Clock.systemUTC());
        gate = gate(Set.of());
    }

    private PluginGate gate(Set<String> required) {
        return new PluginGate(catalog, switches, List.of(), List.of("browser"),
                List.of(new QuotaHook(), new GuardHook(), new AuditHook()), List.of(new GuardAfter(), new TrimAfter()),
                List.of(new GuardWork()), required);
    }

    private ToolContext context(Principal user) throws Exception {
        return new ToolContext(user, new SessionId("s"), new Workspace(root));
    }

    private Tool tool(String name) {
        return catalog.find(name).orElseThrow();
    }

    private PluginGate.PluginView plugin(Principal user, String name) {
        return gate.overview(user).plugins().stream().filter(view -> view.name().equals(name)).findFirst().orElseThrow();
    }

    private PluginGate.ToolView toolView(Principal user, String name) {
        return gate.overview(user).tools().stream().filter(view -> view.name().equals(name)).findFirst().orElseThrow();
    }

    private static ParamView param(List<ParamView> params, String key) {
        return params.stream().filter(view -> view.param().key().equals(key)).findFirst().orElseThrow();
    }

    private ToolContext lastSeenBy(String plugin) {
        // seen 里按调用顺序：guard 的后处理在 trim 前面
        return seen.get(seen.size() - (plugin.equals("guard") ? 2 : 1));
    }

    // ---- 任务 9：声明 ----

    @Test
    void membersOfOnePluginShareOneListInOrder() {
        assertEquals(List.of("tools", "maxChars"), plugin(ALICE, "guard").params().stream().map(view -> view.param().key()).toList());
        assertEquals(List.of("maxChars", "apiKey"), toolView(ALICE, "search").params().stream().map(view -> view.param().key()).toList());
        assertEquals(List.of(), plugin(ALICE, "audit").params());
        assertEquals(List.of(), toolView(ALICE, "read_file").params());
    }

    @Test
    void theSameKeyDeclaredDifferentlyStopsTheStart() {
        AfterToolHook other = new ConflictingAfter();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new PluginGate(catalog, switches, List.of(),
                List.of(), List.of(new GuardHook()), List.of(other), List.of(), Set.of()));
        assertTrue(failure.getMessage().contains("plugin guard"), failure.getMessage());
        assertTrue(failure.getMessage().contains("tools"), failure.getMessage());
    }

    private static final class ConflictingAfter implements AfterToolHook, Configurable {
        @Override public String plugin() { return "guard"; }
        @Override public List<Param> params() { return List.of(Param.list("tools", List.of("something_else"))); }
        @Override public ToolOutput afterToolExecution(ToolContext context, String tool, Json arguments, ToolOutput output) { return output; }
    }

    /** MCP 的工具声明了也不认：没有表单，调用时拿到的是空参数。 */
    @Test
    void paramsOfAnMcpToolAreIgnored() throws Exception {
        Tool remote = new SearchTool() {
            @Override public String name() { return "browser__search"; }
            @Override public String server() { return "mcp:browser"; }
        };
        catalog.setSource(new ToolCatalog.ToolSource() {
            @Override public List<Tool> tools() { return List.of(remote); }
            @Override public String serverName(Tool tool) { return "browser"; }
            @Override public Optional<Tool> find(String name) { return name.equals(remote.name()) ? Optional.of(remote) : Optional.empty(); }
        });
        PluginGate withMcp = gate(Set.of());
        assertEquals(List.of(), withMcp.overview(ALICE).mcp().getFirst().tools().getFirst().params());
        withMcp.runTool(context(ALICE), remote, new Json.Null());
        assertFalse(seen.getLast().params().has("maxChars"));
        assertThrows(PluginGate.UnknownPlugin.class, () -> withMcp.saveParams(ALICE, Kind.TOOL, "browser__search", Map.of("maxChars", num(200))));
    }

    // ---- 任务 10：锁 ----

    @Test
    void threeKindsOfLock() {
        PluginGate required = gate(Set.of("guard"));
        assertEquals(new Lock(Lock.CODE, ""), required.lock(Kind.PLUGIN, "audit"));
        assertEquals(new Lock(Lock.CONFIG, ""), required.lock(Kind.PLUGIN, "guard"));
        assertNull(required.lock(Kind.PLUGIN, "trim"));
        required.setDefault(Kind.PLUGIN, "trim", false, true, " not allowed here ");
        assertEquals(new Lock(Lock.ADMIN, "not allowed here"), required.lock(Kind.PLUGIN, "trim"));
        // 配置里要求开启的照旧对所有人开着、关不掉
        assertTrue(required.pluginEnabled(BOB, "guard"));
        assertThrows(LockedPlugin.class, () -> required.set(BOB, Kind.PLUGIN, "guard", false));
        // 代码和配置定的，管理员在页面上改不了
        assertThrows(LockedPlugin.class, () -> required.setDefault(Kind.PLUGIN, "audit", false, false, ""));
        assertThrows(LockedPlugin.class, () -> required.setDefault(Kind.PLUGIN, "guard", null, true, "x"));
    }

    /** 管理员锁在关上：用户之前开着也算关，开和关都改不了；解锁后用户原来的选择回来。 */
    @Test
    void anAdminLockOverridesTheUsersChoiceUntilItIsLifted() throws Exception {
        gate.set(ALICE, Kind.TOOL, "search", true);
        gate.set(ALICE, Kind.PLUGIN, "quota", true);
        gate.setDefault(Kind.TOOL, "search", false, true, "licence expired");
        gate.setDefault(Kind.PLUGIN, "quota", false, true, "");
        assertFalse(gate.toolEnabled(ALICE, tool("search")));
        assertFalse(gate.pluginEnabled(ALICE, "quota"));
        assertTrue(gate.runTool(context(ALICE), tool("search"), new Json.Null()).error());
        LockedPlugin refused = assertThrows(LockedPlugin.class, () -> gate.set(ALICE, Kind.TOOL, "search", true));
        assertEquals("licence expired", refused.lock().reason());
        assertEquals("search", refused.name());
        assertThrows(LockedPlugin.class, () -> gate.set(ALICE, Kind.PLUGIN, "quota", false));
        assertEquals(new Lock(Lock.ADMIN, "licence expired"), toolView(ALICE, "search").lock());
        assertFalse(toolView(ALICE, "search").changed(), "a locked switch never shows as changed by me");

        gate.setDefault(Kind.TOOL, "search", false, false, "");
        assertTrue(gate.toolEnabled(ALICE, tool("search")), "alice's own choice is back");
        assertFalse(gate.toolEnabled(BOB, tool("search")), "bob never chose: the admin default applies");
        assertTrue(toolView(ALICE, "search").changed());
        assertFalse(toolView(BOB, "search").byDefault());
    }

    /** MCP 服务器也能定默认和锁；开关组不归这里管。 */
    @Test
    void defaultsCoverServersButNotSwitchGroups() {
        gate.setDefault(Kind.MCP, "browser", false, true, "");
        assertFalse(switches.enabled(ALICE, Kind.MCP, "browser"));
        assertThrows(LockedPlugin.class, () -> gate.set(ALICE, Kind.MCP, "browser", true));
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.setDefault(Kind.WEB, "fetch", false, true, ""));
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.setDefault(Kind.PLUGIN, "nope", false, true, ""));
    }

    @Test
    void resetForgetsTheUsersChoice() {
        gate.set(ALICE, Kind.PLUGIN, "quota", true);
        assertTrue(plugin(ALICE, "quota").changed());
        assertFalse(plugin(ALICE, "quota").byDefault());
        gate.reset(ALICE, Kind.PLUGIN, "quota");
        assertFalse(gate.pluginEnabled(ALICE, "quota"));
        assertFalse(plugin(ALICE, "quota").changed());
        // 选的和默认一样不算“我改的”
        gate.set(ALICE, Kind.PLUGIN, "quota", false);
        assertFalse(plugin(ALICE, "quota").changed());
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.reset(ALICE, Kind.PLUGIN, "nope"));
    }

    // ---- 任务 11：送达 ----

    @Test
    void eachMemberGetsItsOwnParamsAndSecrets() throws Exception {
        gate.saveParams(ALICE, Kind.TOOL, "search", Map.of("maxChars", num(200), "apiKey", new Json.Str("tool-secret")));
        gate.saveParams(ALICE, Kind.PLUGIN, "trim", Map.of("maxChars", num(300), "apiKey", new Json.Str("trim-secret")));
        gate.saveParams(ALICE, Kind.PLUGIN, "guard", Map.of("tools", new Json.Arr(List.of(new Json.Str("search")))));

        gate.runTool(context(ALICE), tool("search"), new Json.Null());
        ToolContext forTool = seen.getLast();
        assertEquals(200, forTool.params().number("maxChars", 0));
        assertEquals(Optional.of("tool-secret"), forTool.secrets().get("apiKey"));
        assertFalse(forTool.params().has("tools"), "a tool does not see a plugin's params");

        gate.afterTool(context(ALICE), tool("read_file"), new Json.Null(), ToolOutput.ok("x"));
        ToolContext forGuard = lastSeenBy("guard");
        assertEquals(List.of("search"), forGuard.params().list("tools", List.of()));
        assertEquals(500, forGuard.params().number("maxChars", 0), "guard's own maxChars, not trim's and not the tool's");
        assertEquals(Optional.empty(), forGuard.secrets().get("apiKey"), "guard declared no secret");
        ToolContext forTrim = lastSeenBy("trim");
        assertEquals(300, forTrim.params().number("maxChars", 0));
        assertEquals(Optional.of("trim-secret"), forTrim.secrets().get("apiKey"));

        gate.runWork(context(ALICE), new GuardWork(), new Json.Null());
        assertEquals(List.of("search"), seen.getLast().params().list("tools", List.of()));

        // 别人的不受影响
        gate.runTool(context(BOB), tool("search"), new Json.Null());
        assertEquals(500, seen.getLast().params().number("maxChars", 0));
        assertEquals(Optional.empty(), seen.getLast().secrets().get("apiKey"));

        // 没声明参数的工具拿到的还是原来那个上下文
        ToolContext plain = context(ALICE);
        gate.runTool(plain, tool("read_file"), new Json.Null());
        assertTrue(plain == seen.getLast());
    }

    @Test
    void aChangeAppliesToTheNextCall() throws Exception {
        gate.runTool(context(ALICE), tool("search"), new Json.Null());
        assertEquals(500, seen.getLast().params().number("maxChars", 0));
        gate.saveParams(ALICE, Kind.TOOL, "search", Map.of("maxChars", num(700)));
        gate.runTool(context(ALICE), tool("search"), new Json.Null());
        assertEquals(700, seen.getLast().params().number("maxChars", 0));
        gate.resetParams(ALICE, Kind.TOOL, "search");
        gate.runTool(context(ALICE), tool("search"), new Json.Null());
        assertEquals(500, seen.getLast().params().number("maxChars", 0));
    }

    /** 上下文会被打进日志和异常：里面不能有密码原文。 */
    @Test
    void theContextNeverPrintsASecret() throws Exception {
        gate.saveParams(ALICE, Kind.TOOL, "search", Map.of("apiKey", new Json.Str("hunter2-plain")));
        gate.runTool(context(ALICE), tool("search"), new Json.Null());
        assertEquals(Optional.of("hunter2-plain"), seen.getLast().secrets().get("apiKey"));
        assertFalse(seen.getLast().toString().contains("hunter2"));
    }

    // ---- 任务 12：保存 ----

    @Test
    void aSaveIsCheckedAsAWholeBeforeAnythingIsWritten() {
        InvalidParam outOfRange = assertThrows(InvalidParam.class, () -> gate.saveParams(ALICE, Kind.PLUGIN, "trim",
                Map.of("maxChars", num(5), "apiKey", new Json.Str("should-not-be-stored"))));
        assertEquals("maxChars", outOfRange.key());
        assertEquals("range", outOfRange.reason());
        assertFalse(param(plugin(ALICE, "trim").params(), "apiKey").set());
        assertEquals("builtin", param(plugin(ALICE, "trim").params(), "maxChars").source());

        InvalidParam unknown = assertThrows(InvalidParam.class, () -> gate.saveParams(ALICE, Kind.PLUGIN, "trim", Map.of("nope", num(5))));
        assertEquals("unknown", unknown.reason());
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.saveParams(ALICE, Kind.PLUGIN, "nope", Map.of()));
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.saveParams(ALICE, Kind.MCP, "browser", Map.of()));
    }

    /** 归管理员的参数用户改不了；管理员按人指定，没指定的人用默认。 */
    @Test
    void adminOwnedParamsAreAssignedPerUser() {
        assertThrows(ForbiddenParam.class, () -> gate.saveParams(ALICE, Kind.PLUGIN, "quota", Map.of("tokens", num(999999))));
        gate.saveDefaults(Kind.PLUGIN, "quota", Map.of("tokens", num(800)));
        gate.assign(ALICE, Kind.PLUGIN, "quota", Map.of("tokens", num(50)));
        ParamView alice = param(plugin(ALICE, "quota").params(), "tokens");
        assertEquals(num(50), alice.value());
        assertEquals("assigned", alice.source());
        assertFalse(alice.editable());
        ParamView bob = param(plugin(BOB, "quota").params(), "tokens");
        assertEquals(num(800), bob.value());
        assertEquals("admin", bob.source());

        assertEquals(List.of(new PluginGate.AssignedView(Kind.PLUGIN, "quota",
                List.of(new ParamView(TOKENS, num(50), "assigned", true, false, false)))), gate.assigned(ALICE));
        // JSON null 清掉：回到默认
        gate.assign(ALICE, Kind.PLUGIN, "quota", Map.of("tokens", new Json.Null()));
        assertEquals("admin", param(plugin(ALICE, "quota").params(), "tokens").source());
        gate.saveDefaults(Kind.PLUGIN, "quota", Map.of("tokens", new Json.Null()));
        assertEquals("builtin", param(plugin(ALICE, "quota").params(), "tokens").source());

        // 归用户的键不能按人指定；范围照样查
        assertThrows(ForbiddenParam.class, () -> gate.assign(ALICE, Kind.PLUGIN, "guard", Map.of("maxChars", num(200))));
        assertThrows(InvalidParam.class, () -> gate.assign(ALICE, Kind.PLUGIN, "quota", Map.of("tokens", num(0))));
        assertThrows(InvalidParam.class, () -> gate.saveDefaults(Kind.PLUGIN, "trim", Map.of("apiKey", new Json.Str("no default for secrets"))));
    }

    /** 需求 3.5：锁定后用户自己填的普通参数不算，用管理员的默认；密码照样能存能用；解锁后自己填的回来。 */
    @Test
    void aLockAlsoFreezesTheUsersPlainParams() throws Exception {
        gate.saveParams(ALICE, Kind.PLUGIN, "trim", Map.of("maxChars", num(300)));
        gate.saveDefaults(Kind.PLUGIN, "trim", Map.of("maxChars", num(900)));
        gate.setDefault(Kind.PLUGIN, "trim", true, true, "company policy");

        ParamView locked = param(plugin(ALICE, "trim").params(), "maxChars");
        assertEquals(num(900), locked.value());
        assertEquals("admin", locked.source());
        assertFalse(locked.editable());
        assertThrows(LockedPlugin.class, () -> gate.saveParams(ALICE, Kind.PLUGIN, "trim", Map.of("maxChars", num(400))));
        gate.saveParams(ALICE, Kind.PLUGIN, "trim", Map.of("apiKey", new Json.Str("still-mine")));
        assertTrue(param(plugin(ALICE, "trim").params(), "apiKey").editable());

        gate.afterTool(context(ALICE), tool("read_file"), new Json.Null(), ToolOutput.ok("x"));
        assertEquals(900, lastSeenBy("trim").params().number("maxChars", 0));
        assertEquals(Optional.of("still-mine"), lastSeenBy("trim").secrets().get("apiKey"));

        gate.setDefault(Kind.PLUGIN, "trim", null, false, "");
        assertEquals(num(300), param(plugin(ALICE, "trim").params(), "maxChars").value());
    }

    @Test
    void secretsCanBeReplacedAndCleared() {
        gate.saveParams(ALICE, Kind.TOOL, "search", Map.of("apiKey", new Json.Str("first")));
        ParamView set = param(toolView(ALICE, "search").params(), "apiKey");
        assertTrue(set.set());
        assertFalse(set.stale());
        assertNull(set.value(), "a secret never has a value in the view");
        assertNull(set.source());
        assertFalse(param(toolView(BOB, "search").params(), "apiKey").set());

        // 只改别的参数，密码不动
        gate.saveParams(ALICE, Kind.TOOL, "search", Map.of("maxChars", num(200)));
        assertTrue(param(toolView(ALICE, "search").params(), "apiKey").set());
        // 参数恢复默认也不动密码
        gate.resetParams(ALICE, Kind.TOOL, "search");
        assertTrue(param(toolView(ALICE, "search").params(), "apiKey").set());
        assertEquals("builtin", param(toolView(ALICE, "search").params(), "maxChars").source());

        gate.clearSecret(ALICE, Kind.TOOL, "search", "apiKey");
        assertFalse(param(toolView(ALICE, "search").params(), "apiKey").set());
        assertThrows(InvalidParam.class, () -> gate.clearSecret(ALICE, Kind.TOOL, "search", "maxChars"));
        assertThrows(InvalidParam.class, () -> gate.saveParams(ALICE, Kind.TOOL, "search", Map.of("apiKey", new Json.Str(""))));
    }

    // ---- 任务 13：视图 ----

    @Test
    void pluginsAreGroupedIntoRulesAndOutputProcessing() {
        assertEquals("output", plugin(ALICE, "trim").group());
        assertEquals("rule", plugin(ALICE, "guard").group(), "a hook plus an after-tool hook is a rule");
        assertEquals("rule", plugin(ALICE, "quota").group());
        assertEquals(new Lock(Lock.CODE, ""), plugin(ALICE, "audit").lock());
        assertTrue(plugin(ALICE, "audit").byDefault());
        assertTrue(param(plugin(ALICE, "guard").params(), "tools").editable());
    }

    @Test
    void theAdminSeesEveryDefaultWithoutSecrets() {
        gate.setDefault(Kind.PLUGIN, "quota", true, false, "");
        gate.saveDefaults(Kind.TOOL, "search", Map.of("maxChars", num(900)));
        List<PluginGate.DefaultView> defaults = gate.defaults();
        assertEquals(List.of("tool:read_file", "tool:search", "mcp:browser", "plugin:audit", "plugin:guard", "plugin:quota", "plugin:trim"),
                defaults.stream().map(view -> view.kind().key() + ":" + view.name()).toList());
        PluginGate.DefaultView quota = defaults.stream().filter(view -> view.name().equals("quota")).findFirst().orElseThrow();
        assertFalse(quota.builtin());
        assertEquals(Boolean.TRUE, quota.byAdmin());
        assertNull(quota.lock());
        assertEquals("rule", quota.group());
        PluginGate.DefaultView search = defaults.stream().filter(view -> view.name().equals("search")).findFirst().orElseThrow();
        assertEquals(List.of(new ParamView(MAX_CHARS, num(900), "admin", true, false, false)), search.params());
        assertNull(search.byAdmin());
        assertEquals(new Lock(Lock.CODE, ""), defaults.stream().filter(view -> view.name().equals("audit")).findFirst().orElseThrow().lock());
    }

    // ---- 任务 19：记进会话的钩子插件参数 ----

    /** 插件 vault：钩子声明一个普通参数和一个密码。 */
    static final class VaultHook implements KernelHook, Configurable {
        @Override public String name() { return "vaultHook"; }
        @Override public String plugin() { return "vault"; }
        @Override public List<Param> params() { return List.of(MAX_CHARS, API_KEY); }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) { return Allow.PLAIN; }
    }

    /** 插件 badge：钩子只声明密码。 */
    static final class BadgeHook implements KernelHook, Configurable {
        @Override public String name() { return "badgeHook"; }
        @Override public String plugin() { return "badge"; }
        @Override public List<Param> params() { return List.of(API_KEY); }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) { return Allow.PLAIN; }
    }

    private static Json.Obj obj(Object... pairs) {
        Map<String, Json> fields = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put((String) pairs[i], (Json) pairs[i + 1]);
        }
        return new Json.Obj(fields);
    }

    private static Json list(String... items) {
        return new Json.Arr(java.util.Arrays.stream(items).<Json>map(Json.Str::new).toList());
    }

    /** 只有开着的、有内核钩子的插件，按名字排，键按声明的顺序；每个人是自己那份。 */
    @Test
    void theRecordHoldsEnabledHookPluginsOnly() {
        // quota 默认关；trim 只有后处理；audit 没有参数
        assertEquals(obj("guard", obj("tools", list("write_file"), "maxChars", num(500))), gate.pluginParams(ALICE));
        assertEquals(List.of("tools", "maxChars"), List.copyOf(((Json.Obj) gate.pluginParams(ALICE).fields().get("guard")).fields().keySet()));

        gate.set(ALICE, Kind.PLUGIN, "quota", true);
        gate.saveParams(ALICE, Kind.PLUGIN, "guard", Map.of("maxChars", num(900)));
        gate.assign(ALICE, Kind.PLUGIN, "quota", Map.of("tokens", num(50)));
        Json.Obj alice = gate.pluginParams(ALICE);
        assertEquals(obj("guard", obj("tools", list("write_file"), "maxChars", num(900)), "quota", obj("tokens", num(50))), alice);
        assertEquals(List.of("guard", "quota"), List.copyOf(alice.fields().keySet()));
        assertEquals(obj("guard", obj("tools", list("write_file"), "maxChars", num(500))), gate.pluginParams(BOB));

        gate.set(ALICE, Kind.PLUGIN, "guard", false);
        assertEquals(obj("quota", obj("tokens", num(50))), gate.pluginParams(ALICE));
        gate.set(ALICE, Kind.PLUGIN, "quota", false);
        assertNull(gate.pluginParams(ALICE), "nothing to record is null, so old lines stay as they were");
    }

    /** 锁着的插件：记下的是管理员的默认，不是用户自己填的。 */
    @Test
    void aLockedPluginRecordsTheAdminsValues() {
        gate.saveParams(ALICE, Kind.PLUGIN, "guard", Map.of("maxChars", num(900)));
        gate.saveDefaults(Kind.PLUGIN, "guard", Map.of("maxChars", num(300)));
        gate.setDefault(Kind.PLUGIN, "guard", true, true, "policy");
        assertEquals(num(300), ((Json.Obj) gate.pluginParams(ALICE).fields().get("guard")).fields().get("maxChars"));
    }

    /** 密码不进会话记录：有别的参数的插件只记别的；只有密码的插件整个不记。 */
    @Test
    void secretsNeverEnterTheRecord() {
        PluginGate vault = new PluginGate(catalog, switches, List.of(), List.of(), List.of(new VaultHook(), new BadgeHook()),
                List.of(), List.of(), Set.of());
        vault.saveParams(ALICE, Kind.PLUGIN, "vault", Map.of("apiKey", new Json.Str("hunter2-plain")));
        vault.saveParams(ALICE, Kind.PLUGIN, "badge", Map.of("apiKey", new Json.Str("hunter2-badge")));
        Json.Obj recorded = vault.pluginParams(ALICE);
        assertEquals(obj("vault", obj("maxChars", num(500))), recorded);
        assertFalse(recorded.toString().contains("hunter2"));
    }

    // ---- input-area 需求 6.2、6.3：每日上限 ----

    @Test
    void theDailyLimitIsTheLowestAmongEnabledPluginsThatReportOne() {
        assertEquals(OptionalLong.empty(), gate.dailyLimit(ALICE), "quota is off, guard reports none");

        gate.set(ALICE, Kind.PLUGIN, "quota", true);
        assertEquals(OptionalLong.of(1000), gate.dailyLimit(ALICE), "the declared default reaches the member");
        assertEquals(OptionalLong.empty(), gate.dailyLimit(BOB));

        gate.assign(ALICE, Kind.PLUGIN, "quota", Map.of("tokens", num(300)));
        assertEquals(OptionalLong.of(300), gate.dailyLimit(ALICE), "what the administrator assigned to this user");

        guardLimit = OptionalLong.of(800);
        assertEquals(OptionalLong.of(300), gate.dailyLimit(ALICE));
        assertEquals(OptionalLong.of(800), gate.dailyLimit(BOB), "guard is on for everyone");
        guardLimit = OptionalLong.of(200);
        assertEquals(OptionalLong.of(200), gate.dailyLimit(ALICE));

        gate.set(ALICE, Kind.PLUGIN, "quota", false);
        guardLimit = OptionalLong.empty();
        assertEquals(OptionalLong.empty(), gate.dailyLimit(ALICE));
    }

    /** qagent-align 需求 5.1、5.2、5.6、5.8：报上限的插件说明上限是哪个参数，运维页据此显示“默认 / 单独设置”并直接改。 */
    @Test
    void thePluginSaysWhichParamIsTheLimitSoTheOpsPageCanShowAndChangeIt() {
        // guard 也报上限（按名字排在 quota 前面），但没说是哪个参数：能改的是 quota 的 tokens
        PluginGate.LimitSource source = gate.limitSource().orElseThrow();
        assertEquals("quota", source.plugin());
        assertEquals("tokens", source.param().key());
        assertEquals(1000, source.value(), "the plugin's own default");
        assertEquals(1L, source.param().min());

        // 他没开报上限的插件（guard 现在不报）：页面写“没有上限”
        PluginGate.UserLimit off = gate.limitOf(ALICE);
        assertTrue(off.reported());
        assertTrue(off.enabled(), "guard is on for everyone, though it reports no number now");
        assertEquals(OptionalLong.empty(), off.tokens());

        gate.set(ALICE, Kind.PLUGIN, "quota", true);
        assertEquals(new PluginGate.UserLimit(true, true, OptionalLong.of(1000), false), gate.limitOf(ALICE));

        // 管理员改默认：所有人的默认变了，还是“默认”
        gate.saveDefaults(Kind.PLUGIN, "quota", Map.of("tokens", num(5000)));
        assertEquals(5000, gate.limitSource().orElseThrow().value());
        assertEquals(new PluginGate.UserLimit(true, true, OptionalLong.of(5000), false), gate.limitOf(ALICE));

        // 给这个人单独设；别人不受影响
        gate.assign(ALICE, Kind.PLUGIN, "quota", Map.of("tokens", num(300)));
        assertEquals(new PluginGate.UserLimit(true, true, OptionalLong.of(300), true), gate.limitOf(ALICE));
        gate.set(BOB, Kind.PLUGIN, "quota", true);
        assertEquals(new PluginGate.UserLimit(true, true, OptionalLong.of(5000), false), gate.limitOf(BOB));
        assertEquals(5000, gate.limitSource().orElseThrow().value(), "the default is not what one person was given");

        // 恢复默认
        gate.assign(ALICE, Kind.PLUGIN, "quota", Map.of("tokens", new Json.Null()));
        assertEquals(new PluginGate.UserLimit(true, true, OptionalLong.of(5000), false), gate.limitOf(ALICE));

        // 多个插件报上限：显示最小的，“单独设置”说的仍然是能改的那个参数
        guardLimit = OptionalLong.of(200);
        assertEquals(new PluginGate.UserLimit(true, true, OptionalLong.of(200), false), gate.limitOf(ALICE));
    }
}
