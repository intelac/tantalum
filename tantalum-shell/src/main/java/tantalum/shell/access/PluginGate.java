package tantalum.shell.access;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.access.PluginSwitches.Kind;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.tool.ToolCatalog;
import tantalum.value.hook.HookContext;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;
import tantalum.value.state.KernelState;

/**
 * 插件的唯一入口（core-tools C16）：组装上下文时给出这个用户开着的工具和钩子插件；执行工具、工具后处理和插件工作前再按当前开关查一次。
 * 除了这里，没有代码调用 {@link Tool#run}、{@link AfterToolHook#afterToolExecution}、{@link WorkType#run}（ArchUnit 规则钉住）。
 * 内核钩子在内核里运行，由 {@code HookChain} 按会话记下的插件名单过滤。
 */
public final class PluginGate {

    private static final Logger LOG = LoggerFactory.getLogger(PluginGate.class);

    public static final String TOOL_OFF = "This tool was turned off in the plugin page and is not available.";
    static final List<String> HOOK_POINTS =
            List.of("beforeModelCall", "beforeToolCall", "onFailure", "beforeRunEnd", "onWorkResult", "beforeCompact");

    /** 一个钩子插件：{@link KernelHook#plugin()} 等返回同一个名字的钩子、工具后处理和插件工作，在插件页上是一项（C13）。 */
    public record Plugin(String name, String description, List<String> points, boolean mandatory) {}

    private final ToolCatalog catalog;
    private final PluginSwitches switches;
    private final List<String> mcpServers;
    private final List<AfterToolHook> afterHooks;
    private final List<WorkType> workTypes;
    private final Map<String, Plugin> plugins;

    /** {@code mcpServers} 是配置了的 MCP 服务器名，没连上的也算，用户可以先关掉。 */
    public PluginGate(ToolCatalog catalog, PluginSwitches switches, List<String> mcpServers, List<KernelHook> hooks,
                      List<AfterToolHook> afterHooks, List<WorkType> workTypes) {
        this.catalog = catalog;
        this.switches = switches;
        this.mcpServers = List.copyOf(mcpServers);
        this.afterHooks = List.copyOf(afterHooks);
        this.workTypes = List.copyOf(workTypes);
        this.plugins = group(hooks, afterHooks, workTypes);
    }

    /** 没有用户开关的入口：一切都开着（嵌入使用和测试）。仍然是唯一的调用入口。 */
    public static PluginGate allOn(ToolCatalog catalog, List<KernelHook> hooks, List<AfterToolHook> afterHooks, List<WorkType> workTypes) {
        try {
            return new PluginGate(catalog, PluginSwitches.inMemory(java.time.Clock.systemUTC()), List.of(), hooks, afterHooks, workTypes);
        } catch (java.sql.SQLException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ---- 名单 ----

    /** 内置工具看自己的开关；MCP 工具还要看所属服务器的开关。 */
    public boolean toolEnabled(Principal user, Tool tool) {
        if (!switches.enabled(user, Kind.TOOL, tool.name())) {
            return false;
        }
        return catalog.isRegistered(tool.name()) || switches.enabled(user, Kind.MCP, tool.server());
    }

    /** 这个用户现在开着的工具名，按名称排序（同 {@link ToolCatalog#specs()}）。 */
    public List<String> enabledTools(Principal user) {
        return catalog.tools().stream().filter(tool -> toolEnabled(user, tool)).map(Tool::name).toList();
    }

    public boolean pluginEnabled(Principal user, String plugin) {
        Plugin known = plugins.get(plugin);
        return known != null && (known.mandatory() || switches.enabled(user, Kind.PLUGIN, plugin));
    }

    public List<String> enabledPlugins(Principal user) {
        return plugins.keySet().stream().filter(name -> pluginEnabled(user, name)).toList();
    }

    public List<Plugin> plugins() {
        return List.copyOf(plugins.values());
    }

    /** 注册了的工作类型，{@code WorkHandler} 按名字找。 */
    public List<WorkType> workTypes() {
        return workTypes;
    }

    // ---- 插件页 ----

    /** 一个开关在插件页上的样子：开关本身的状态，不叠加服务器的开关。 */
    public record ToolView(String name, String description, boolean enabled) {}

    public record ServerView(String name, boolean enabled, List<ToolView> tools) {}

    public record PluginView(String name, String description, List<String> points, boolean mandatory, boolean enabled) {}

    public record Overview(List<ToolView> tools, List<ServerView> mcp, List<PluginView> plugins) {}

    /** 这个用户的插件页：内置工具、MCP 服务器和它们现在的工具、钩子插件。 */
    public Overview overview(Principal user) {
        List<ToolView> builtins = new ArrayList<>();
        Map<String, List<ToolView>> byServer = new LinkedHashMap<>();
        mcpServers.forEach(server -> byServer.put(server, new ArrayList<>()));
        for (Tool tool : catalog.tools()) {
            ToolView view = new ToolView(tool.name(), tool.description(), switches.enabled(user, Kind.TOOL, tool.name()));
            if (catalog.isRegistered(tool.name())) {
                builtins.add(view);
            } else {
                byServer.computeIfAbsent(tool.server(), ignored -> new ArrayList<>()).add(view);
            }
        }
        List<ServerView> servers = byServer.entrySet().stream()
                .map(entry -> new ServerView(entry.getKey(), switches.enabled(user, Kind.MCP, entry.getKey()), List.copyOf(entry.getValue())))
                .toList();
        List<PluginView> views = plugins.values().stream()
                .map(plugin -> new PluginView(plugin.name(), plugin.description(), plugin.points(), plugin.mandatory(),
                        pluginEnabled(user, plugin.name())))
                .toList();
        return new Overview(List.copyOf(builtins), servers, views);
    }

    // ---- 调用 ----

    /** 执行前按当前开关查：关掉的立即拒绝，不执行（需求 8 第 3 条）。 */
    public ToolOutput runTool(ToolContext context, Tool tool, Json arguments) throws Exception {
        if (!toolEnabled(context.principal(), tool)) {
            return ToolOutput.error(TOOL_OFF);
        }
        return tool.run(context, arguments);
    }

    /** 只跑开着的插件的工具后处理；一个出错记日志，结果保持上一个。 */
    public ToolOutput afterTool(ToolContext context, Tool tool, Json arguments, ToolOutput first) {
        ToolOutput output = first;
        for (AfterToolHook hook : afterHooks) {
            if (!pluginEnabled(context.principal(), hook.plugin())) {
                continue;
            }
            try {
                output = hook.afterToolExecution(context, tool.name(), arguments, output);
            } catch (Exception failure) {
                LOG.error("afterToolExecution hook failed for tool {}", tool.name(), failure);
            }
        }
        return output;
    }

    /** 插件关掉了就不做这次工作，以失败结束。 */
    public WorkResult runWork(ToolContext context, WorkType type, Json input) throws Exception {
        if (!pluginEnabled(context.principal(), type.plugin())) {
            throw new IllegalStateException("plugin " + type.plugin() + " was turned off in the plugin page");
        }
        return type.run(context, input);
    }

    // ---- 开关 ----

    /** 只改这个用户自己的。不存在的名字抛 {@link UnknownPlugin}；必须开启的插件不能关，抛 {@link IllegalArgumentException}。 */
    public void set(Principal user, Kind kind, String name, boolean enabled) {
        switch (kind) {
            case TOOL -> {
                if (catalog.find(name).isEmpty()) {
                    throw new UnknownPlugin(kind, name);
                }
            }
            case MCP -> {
                if (!mcpServers.contains(name)) {
                    throw new UnknownPlugin(kind, name);
                }
            }
            case PLUGIN -> {
                Plugin plugin = plugins.get(name);
                if (plugin == null) {
                    throw new UnknownPlugin(kind, name);
                }
                if (plugin.mandatory() && !enabled) {
                    throw new IllegalArgumentException("plugin " + name + " is required and cannot be turned off");
                }
            }
        }
        switches.set(user, kind, name, enabled);
    }

    public static final class UnknownPlugin extends RuntimeException {
        UnknownPlugin(Kind kind, String name) {
            super("no such " + kind.key() + ": " + name);
        }
    }

    private static Map<String, Plugin> group(List<KernelHook> hooks, List<AfterToolHook> afterHooks, List<WorkType> workTypes) {
        Map<String, List<String>> points = new LinkedHashMap<>();
        Map<String, String> descriptions = new LinkedHashMap<>();
        Map<String, Boolean> mandatory = new LinkedHashMap<>();
        for (KernelHook hook : hooks) {
            List<String> list = points.computeIfAbsent(hook.plugin(), ignored -> new ArrayList<>());
            for (String point : HOOK_POINTS) {
                if (overrides(hook, point)) {
                    list.add(point);
                }
            }
            descriptions.merge(hook.plugin(), hook.description(), PluginGate::first);
            mandatory.merge(hook.plugin(), hook.mandatory(), Boolean::logicalOr);
        }
        for (AfterToolHook hook : afterHooks) {
            points.computeIfAbsent(hook.plugin(), ignored -> new ArrayList<>()).add("afterToolExecution");
            descriptions.merge(hook.plugin(), hook.description(), PluginGate::first);
        }
        for (WorkType type : workTypes) {
            points.computeIfAbsent(type.plugin(), ignored -> new ArrayList<>()).add("work:" + type.name());
            descriptions.merge(type.plugin(), type.description(), PluginGate::first);
        }
        Map<String, Plugin> grouped = new LinkedHashMap<>();
        points.keySet().stream().sorted().forEach(name -> grouped.put(name, new Plugin(name, descriptions.getOrDefault(name, ""),
                points.get(name).stream().distinct().toList(), mandatory.getOrDefault(name, false))));
        return grouped;
    }

    private static String first(String earlier, String later) {
        return earlier.isEmpty() ? later : earlier;
    }

    private static boolean overrides(KernelHook hook, String point) {
        try {
            return hook.getClass().getMethod(point, KernelState.class, HookContext.class).getDeclaringClass() != KernelHook.class;
        } catch (NoSuchMethodException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
