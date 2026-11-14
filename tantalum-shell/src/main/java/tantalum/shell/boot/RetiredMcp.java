package tantalum.shell.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * 服务器端的 MCP 客户端去掉了（remove-generic-mcp，Yi 2026-11-14：“就算他是mcp 我们也要封装在plugin里”）。
 * 配置里还写着 {@code tantalum.mcp.*}（或环境变量 {@code TANTALUM_MCP_…}）时照常启动，WARN 一行，只列键名不列值（需求 1.2）。
 * 同 {@link RetiredBrowser}：这些键没有新名字，写着也无害，不像改了名的配置那样拒绝启动（{@link RemovedKeys}）。
 */
final class RetiredMcp {

    private static final Logger LOG = LoggerFactory.getLogger(RetiredMcp.class);
    static final String MESSAGE = "These settings no longer do anything and can be removed: {}. Tantalum no longer connects to MCP servers; "
            + "to use one, wrap it in a plugin that provides tools (the browser does this with Playwright MCP).";

    private RetiredMcp() {}

    /** 还写着的旧键，去重，按出现的顺序。 */
    static List<String> staleKeys(Iterable<String> names) {
        List<String> stale = new ArrayList<>();
        for (String name : names) {
            boolean dotted = name.toLowerCase(Locale.ROOT).startsWith("tantalum.mcp.");
            boolean variable = name.startsWith("TANTALUM_MCP_");
            if ((dotted || variable) && !stale.contains(name)) {
                stale.add(name);
            }
        }
        return stale;
    }

    /** 启动时调：命令行、环境变量、配置文件里的都查；有就 WARN 一行。 */
    static void atStartup(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                names.addAll(List.of(enumerable.getPropertyNames()));
            }
        }
        List<String> stale = staleKeys(names);
        if (!stale.isEmpty()) {
            LOG.warn(MESSAGE, String.join(", ", stale));
        }
    }
}
