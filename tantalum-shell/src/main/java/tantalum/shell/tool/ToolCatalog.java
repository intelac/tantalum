package tantalum.shell.tool;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tantalum.kernel.tool.ToolSafety;
import tantalum.shell.handler.CallLimiter;
import tantalum.shell.plugin.Tool;
import tantalum.value.model.ToolSpec;

/** 已注册的工具，以及每个服务端共享的并发名额。动态工具（如 MCP）来自 {@link ToolSource}，同名时已注册的工具优先。 */
public final class ToolCatalog {

    /** 运行中会变化的工具来源。必须线程安全。 */
    public interface ToolSource {
        List<Tool> tools();

        Optional<Tool> find(String name);
    }

    private volatile ToolSource source = new ToolSource() {
        @Override public List<Tool> tools() { return List.of(); }
        @Override public Optional<Tool> find(String name) { return Optional.empty(); }
    };

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Map<String, CallLimiter> limiters = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Integer> limits = new HashMap<>();

    public void register(Tool tool) {
        if (tools.containsKey(tool.name())) {
            throw new IllegalStateException("tool already registered: " + tool.name());
        }
        Integer declared = limits.putIfAbsent(tool.server(), tool.serverConcurrency());
        if (declared != null && declared != tool.serverConcurrency()) {
            throw new IllegalStateException("server " + tool.server() + " declared with limits "
                    + declared + " and " + tool.serverConcurrency());
        }
        limiters.computeIfAbsent(tool.server(), server -> new CallLimiter(tool.serverConcurrency()));
        tools.put(tool.name(), tool);
    }

    public void setSource(ToolSource newSource) {
        source = newSource;
    }

    public Optional<Tool> find(String name) {
        Tool registered = tools.get(name);
        return registered != null ? Optional.of(registered) : source.find(name);
    }

    public CallLimiter limiterFor(Tool tool) {
        return limiters.computeIfAbsent(tool.server(), server -> new CallLimiter(tool.serverConcurrency()));
    }

    /** 按名称排序，工具顺序不随注册或连接的先后变化，缓存前缀才稳定。 */
    public List<ToolSpec> specs() {
        return java.util.stream.Stream.concat(tools.values().stream(),
                        source.tools().stream().filter(tool -> !tools.containsKey(tool.name())))
                .sorted(java.util.Comparator.comparing(Tool::name))
                .map(tool -> new ToolSpec(tool.name(), tool.description(), tool.schema())).toList();
    }

    /** 给内核用的判断。未注册的工具按独占处理；工具抛的异常由内核按独占处理。 */
    public ToolSafety safety() {
        return (name, arguments) -> {
            Tool tool = tools.get(name);
            return tool != null && tool.isConcurrencySafe(arguments);
        };
    }
}
