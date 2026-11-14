package tantalum.shell.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tantalum.shell.plugin.Kind;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.tool.ToolCatalog;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;

/** web-tools W10、需求 5.1–5.3：联网这一组的开关。 */
class WebAccessTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");

    private PluginSwitches switches;

    /** 联网工具：实现 {@link WebTool}（同 web_fetch）。 */
    private static Tool webTool(String name, String server) {
        return new WebTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Json schema() { return new Json.Obj(Map.of()); }
            @Override public String server() { return server; }
            @Override public ToolOutput run(ToolContext context, Json arguments) { return ToolOutput.ok(name); }
        };
    }

    private static Tool tool(String name, String server) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Json schema() { return new Json.Obj(Map.of()); }
            @Override public String server() { return server; }
            @Override public ToolOutput run(ToolContext context, Json arguments) { return ToolOutput.ok(name); }
        };
    }

    @BeforeEach
    void setUp() throws Exception {
        switches = PluginSwitches.inMemory(Clock.systemUTC());
    }

    /** 管理员开着抓网页才注册工具（同 Engine）；read_attachment 是内置工具。 */
    private PluginGate gate(boolean fetchAllowed) {
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(tool("read_attachment", "attachments"));
        if (fetchAllowed) {
            catalog.register(webTool("web_fetch", WebAccess.FETCH));
        }
        return new PluginGate(catalog, switches, List.of(new WebAccess(switches, fetchAllowed, false)),
                List.of(), List.of(), List.of(), Set.of());
    }

    /** 能用 = 管理员开着（注册了）× 总开关 × 子开关，逐个组合（5.1–5.3）。内置工具不受联网开关影响。 */
    @Test
    void aWebToolIsUsableOnlyWhenAdminMasterAndItsOwnSwitchAreAllOn() {
        boolean[] both = {true, false};
        for (boolean fetchAllowed : both) {
            for (boolean master : both) {
                for (boolean fetch : both) {
                    PluginGate gate = gate(fetchAllowed);
                    gate.set(ALICE, Kind.WEB, WebAccess.MASTER, master);
                    gate.set(ALICE, Kind.WEB, WebAccess.FETCH, fetch);
                    List<String> expected = new ArrayList<>();
                    if (fetchAllowed && master && fetch) {
                        expected.add("web_fetch");
                    }
                    List<String> enabled = new ArrayList<>(gate.enabledTools(ALICE));
                    assertTrue(enabled.remove("read_attachment"), "builtin tools stay on");
                    assertEquals(expected, enabled, "admin " + fetchAllowed + ", switches " + master + "/" + fetch);
                }
            }
        }
    }

    /** 5.1：默认都开；一个用户关掉只影响他自己。 */
    @Test
    void switchesDefaultOnAndArePerUser() {
        PluginGate gate = gate(true);
        WebAccess web = new WebAccess(switches, false, true);
        assertEquals(Map.of("web", true, "web-fetch", true), web.switches(ALICE));
        gate.set(ALICE, Kind.WEB, WebAccess.FETCH, false);
        assertEquals(List.of("web", "web-fetch"), List.copyOf(web.switches(ALICE).keySet()));
        assertEquals(false, web.switches(ALICE).get("web-fetch"));
        assertTrue(gate.enabledTools(BOB).contains("web_fetch"));
        assertEquals(Map.of("web-fetch", false), web.admin());
        assertTrue(web.blockPrivateNetwork());
    }

    /** 联网工具在自己的卡片上，不进“内置工具”，也没有单独的工具开关；来源记为 web。 */
    @Test
    void webToolsAreNotBuiltinsAndTheirSourceIsWeb() {
        PluginGate gate = gate(true);
        assertEquals(List.of("read_attachment"), gate.overview(ALICE).tools().stream().map(PluginGate.ToolView::name).toList());
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.set(ALICE, Kind.TOOL, "web_fetch", false));
        assertEquals("web", gate.source(webTool("web_fetch", WebAccess.FETCH)));
        assertEquals("builtin", gate.source(tool("read_attachment", "attachments")));
    }

    /** 名字不在这一组里的拒绝；管理员关掉的那一项照样能存（页面上禁用）。 */
    @Test
    void onlyTheGroupsOwnSwitchNamesAreAccepted() {
        PluginGate gate = gate(false);
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.set(ALICE, Kind.WEB, "web_fetch", false));
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.set(ALICE, Kind.WEB, "fs-read", false));
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.set(ALICE, Kind.LOCAL, "local-workspace", false), "no local group here");
        gate.set(ALICE, Kind.WEB, WebAccess.FETCH, false);
        assertEquals(false, switches.enabled(ALICE, Kind.WEB, WebAccess.FETCH));
    }

    /**
     * local-browser 需求 8.7：浏览器搬到用户电脑上以后，“联网 › 浏览器”不再是这一组的开关。以前存下的值留在库里没人读：
     * 不报错，不影响抓网页；再去设它被拒绝；一个还带着旧服务端名的联网工具也不归这一组管了。
     */
    @Test
    void theOldBrowserSwitchIsIgnoredNotAnError() {
        switches.set(ALICE, Kind.WEB, "browser", false);
        PluginGate gate = gate(true);
        WebAccess web = new WebAccess(switches, true, false);
        assertEquals(Map.of("web", true, "web-fetch", true), web.switches(ALICE));
        assertTrue(gate.enabledTools(ALICE).contains("web_fetch"));
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.set(ALICE, Kind.WEB, "browser", true));
        assertEquals(false, web.owns(webTool("browser_click", "browser")));
    }

    /**
     * 按类型认，不按名字认：名叫 browser 的 MCP 服务器（服务端名 mcp:browser），和名字碰巧是 browser、web-fetch 的插件工具
     * （{@code server()} 默认是工具名）都不是联网工具，不跟联网的开关走，来源也不是 web。
     */
    @Test
    void onlyWebToolsAreOwnedNotToolsThatShareTheirServerName() {
        WebAccess web = new WebAccess(switches, true, false);
        assertEquals(false, web.owns(tool("mcp__browser__snapshot", "mcp:browser")));
        Tool plugin = tool("browser", "browser");
        assertEquals(false, web.owns(plugin));
        assertEquals(false, web.owns(tool("web-fetch", "web-fetch")));
        assertTrue(web.owns(webTool("web_fetch", WebAccess.FETCH)));

        ToolCatalog catalog = new ToolCatalog();
        catalog.register(plugin);
        PluginGate gate = new PluginGate(catalog, switches, List.of(web), List.of(), List.of(), List.of(), Set.of());
        gate.set(ALICE, Kind.WEB, WebAccess.MASTER, false);
        assertEquals(List.of("browser"), List.copyOf(gate.enabledTools(ALICE)), "the web switch does not turn off a plugin tool");
        assertEquals("builtin", gate.source(plugin));
    }
}
