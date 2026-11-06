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
import tantalum.shell.access.PluginSwitches.Kind;
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

    /** 管理员开着的项才注册工具（同 Engine）；read_attachment 是内置工具。 */
    private PluginGate gate(boolean fetchAllowed, boolean browserAllowed) {
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(tool("read_attachment", "attachments"));
        if (fetchAllowed) {
            catalog.register(tool("web_fetch", WebAccess.FETCH));
        }
        if (browserAllowed) {
            catalog.register(tool("browser_navigate", WebAccess.BROWSER));
            catalog.register(tool("browser_snapshot", WebAccess.BROWSER));
        }
        return new PluginGate(catalog, switches, List.of(new WebAccess(switches, fetchAllowed, browserAllowed, false)), List.of(),
                List.of(), List.of(), List.of(), Set.of());
    }

    /** 能用 = 管理员开着（注册了）× 总开关 × 子开关，逐个组合（5.1–5.3）。内置工具不受联网开关影响。 */
    @Test
    void aWebToolIsUsableOnlyWhenAdminMasterAndItsOwnSwitchAreAllOn() {
        boolean[] both = {true, false};
        for (boolean fetchAllowed : both) {
            for (boolean browserAllowed : both) {
                for (boolean master : both) {
                    for (boolean fetch : both) {
                        for (boolean browser : both) {
                            PluginGate gate = gate(fetchAllowed, browserAllowed);
                            gate.set(ALICE, Kind.WEB, WebAccess.MASTER, master);
                            gate.set(ALICE, Kind.WEB, WebAccess.FETCH, fetch);
                            gate.set(ALICE, Kind.WEB, WebAccess.BROWSER, browser);
                            List<String> expected = new ArrayList<>();
                            if (fetchAllowed && master && fetch) {
                                expected.add("web_fetch");
                            }
                            if (browserAllowed && master && browser) {
                                expected.addAll(0, List.of("browser_navigate", "browser_snapshot"));
                            }
                            List<String> enabled = new ArrayList<>(gate.enabledTools(ALICE));
                            assertTrue(enabled.remove("read_attachment"), "builtin tools stay on");
                            assertEquals(expected, enabled, "admin " + fetchAllowed + "/" + browserAllowed + ", switches " + master + "/"
                                    + fetch + "/" + browser);
                        }
                    }
                }
            }
        }
    }

    /** 5.1：默认都开；一个用户关掉只影响他自己。 */
    @Test
    void switchesDefaultOnAndArePerUser() {
        PluginGate gate = gate(true, true);
        WebAccess web = new WebAccess(switches, true, false, true);
        assertEquals(Map.of("web", true, "web-fetch", true, "browser", true), web.switches(ALICE));
        gate.set(ALICE, Kind.WEB, WebAccess.FETCH, false);
        assertEquals(List.of("web", "web-fetch", "browser"), List.copyOf(web.switches(ALICE).keySet()));
        assertEquals(false, web.switches(ALICE).get("web-fetch"));
        assertTrue(gate.enabledTools(BOB).contains("web_fetch"));
        assertEquals(Map.of("web-fetch", true, "browser", false), web.admin());
        assertTrue(web.blockPrivateNetwork());
    }

    /** 联网工具在自己的卡片上，不进“内置工具”，也没有单独的工具开关；来源记为 web。 */
    @Test
    void webToolsAreNotBuiltinsAndTheirSourceIsWeb() {
        PluginGate gate = gate(true, true);
        assertEquals(List.of("read_attachment"), gate.overview(ALICE).tools().stream().map(PluginGate.ToolView::name).toList());
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.set(ALICE, Kind.TOOL, "web_fetch", false));
        assertEquals("web", gate.source(tool("web_fetch", WebAccess.FETCH)));
        assertEquals("web", gate.source(tool("browser_click", WebAccess.BROWSER)));
        assertEquals("builtin", gate.source(tool("read_attachment", "attachments")));
    }

    /** 名字不在这一组里的拒绝；管理员关掉的那一项照样能存（页面上禁用）。 */
    @Test
    void onlyTheGroupsOwnSwitchNamesAreAccepted() {
        PluginGate gate = gate(false, false);
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.set(ALICE, Kind.WEB, "web_fetch", false));
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.set(ALICE, Kind.WEB, "fs-read", false));
        assertThrows(PluginGate.UnknownPlugin.class, () -> gate.set(ALICE, Kind.LOCAL, "local-workspace", false), "no local group here");
        gate.set(ALICE, Kind.WEB, WebAccess.BROWSER, false);
        assertEquals(false, switches.enabled(ALICE, Kind.WEB, WebAccess.BROWSER));
    }

    /** MCP 工具的服务端名带 mcp: 前缀，名叫 browser 的 MCP 服务器不会被当成联网工具。 */
    @Test
    void anMcpServerNamedBrowserIsNotTheBrowser() {
        WebAccess web = new WebAccess(switches, true, true, false);
        assertEquals(false, web.owns(tool("mcp__browser__snapshot", "mcp:browser")));
    }
}
