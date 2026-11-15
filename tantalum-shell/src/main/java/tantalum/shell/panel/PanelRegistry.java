package tantalum.shell.panel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 装上的面板（plugin-panel 需求 1）：启动时检查 ID、重名和入口页面在不在，之后只读。
 * 列表按用户的插件开关过滤，开关的判断由调用方给（{@code PluginGate.pluginEnabled}）。
 */
public final class PanelRegistry {

    /** 列表里的一个面板；{@code title} 是 {@code LocalText.wire()}，控制台按语言选。 */
    public record Listed(String name, Object title, String icon, String url) {}

    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    private final Map<String, Panel> panels = new LinkedHashMap<>();

    /** {@code loader} 用来找面板文件。ID 不合法、重名、入口页面找不到时抛 {@link IllegalArgumentException}。 */
    public PanelRegistry(List<Panel> panels, ClassLoader loader) {
        for (Panel panel : panels) {
            String name = panel.name();
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("panel name must be 1-40 lowercase letters, digits or hyphens: " + name
                        + " (" + panel.getClass().getName() + ")");
            }
            Panel earlier = this.panels.putIfAbsent(name, panel);
            if (earlier != null) {
                throw new IllegalArgumentException("two panels are both named " + name + ": " + earlier.getClass().getName() + " and "
                        + panel.getClass().getName());
            }
            String base = panel.base();
            if (base == null || !base.endsWith("/") || base.startsWith("/") || base.contains("..")) {
                throw new IllegalArgumentException("panel " + name + " base must be a relative classpath folder ending with /: " + base);
            }
            if (loader.getResource(base + panel.entry()) == null) {
                throw new IllegalArgumentException("panel " + name + " entry " + base + panel.entry() + " is not on the classpath");
            }
        }
    }

    public static PanelRegistry empty() {
        return new PanelRegistry(List.of(), PanelRegistry.class.getClassLoader());
    }

    public Optional<Panel> find(String name) {
        return Optional.ofNullable(panels.get(name));
    }

    public List<Panel> all() {
        return List.copyOf(panels.values());
    }

    /** 所属插件开着的面板。 */
    public List<Listed> list(Predicate<String> pluginEnabled) {
        List<Listed> listed = new ArrayList<>();
        for (Panel panel : panels.values()) {
            if (pluginEnabled.test(panel.plugin())) {
                listed.add(new Listed(panel.name(), panel.title().wire(), panel.icon(), "/panels/" + panel.name() + "/" + panel.entry()));
            }
        }
        return listed;
    }
}
