package tantalum.shell.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tantalum.shell.access.PluginGate;
import tantalum.shell.access.PluginSwitches;
import tantalum.shell.param.ParamStore;
import tantalum.shell.param.SecretBox;
import tantalum.shell.plugin.Kind;
import tantalum.shell.tool.ToolCatalog;
import tantalum.value.id.Principal;
import tantalum.value.text.LocalText;

/** plugin-panel 需求 1：面板的 ID、重名、入口页面，列表跟着插件开关，只有面板的插件在插件页上有开关。 */
class PanelRegistryTest {

    static Panel panel(String name, String base, String entry) {
        return new Panel() {
            @Override public String name() { return name; }
            @Override public LocalText title() { return LocalText.of("Probe", "探针", "プローブ"); }
            @Override public String base() { return base; }
            @Override public String entry() { return entry; }
            @Override public String icon() { return "activity"; }
        };
    }

    static final Panel PROBE = panel("probe", "tantalum/panels/probe/", "index.html");
    private static final ClassLoader LOADER = PanelRegistryTest.class.getClassLoader();

    @Test
    void badNamesDuplicatesAndMissingEntriesFailAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new PanelRegistry(List.of(panel("Bad_Name", "tantalum/panels/probe/", "index.html")), LOADER));
        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class, () -> new PanelRegistry(List.of(PROBE, PROBE), LOADER));
        assertTrue(twice.getMessage().contains("two panels are both named probe"), twice.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new PanelRegistry(List.of(panel("x", "tantalum/panels/probe/", "missing.html")), LOADER));
        assertThrows(IllegalArgumentException.class, () -> new PanelRegistry(List.of(panel("x", "/abs/", "index.html")), LOADER));
        assertThrows(IllegalArgumentException.class, () -> new PanelRegistry(List.of(panel("x", "tantalum/panels/../panels/probe/", "index.html")), LOADER));
    }

    @Test
    void theListFollowsThePluginSwitch() throws SQLException {
        PanelRegistry registry = new PanelRegistry(List.of(PROBE), LOADER);
        PluginSwitches switches = PluginSwitches.inMemory(Clock.systemUTC());
        PluginGate gate = new PluginGate(new ToolCatalog(), switches, List.of(), List.of(), List.of(), List.of(), List.of(PROBE), Set.of(),
                ParamStore.inMemory(Clock.systemUTC(), new SecretBox(new byte[32])));
        Principal yi = new Principal("yi");
        assertEquals(List.of(new PanelRegistry.Listed("probe", PROBE.title().wire(), "activity", "/panels/probe/index.html")),
                registry.list(plugin -> gate.pluginEnabled(yi, plugin)));
        // 只有面板的插件在插件页上也有一行，挂在 panel:probe
        assertEquals(List.of("panel:probe"), gate.plugins().stream().filter(plugin -> plugin.name().equals("probe")).findFirst().orElseThrow().points());
        switches.set(yi, Kind.PLUGIN, "probe", false);
        assertEquals(List.of(), registry.list(plugin -> gate.pluginEnabled(yi, plugin)));
    }
}
