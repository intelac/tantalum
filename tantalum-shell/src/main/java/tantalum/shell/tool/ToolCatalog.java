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

/** 已注册的工具，以及每个服务端共享的并发名额。 */
public final class ToolCatalog {

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Map<String, CallLimiter> limiters = new HashMap<>();
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

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public CallLimiter limiterFor(Tool tool) {
        return limiters.get(tool.server());
    }

    public List<ToolSpec> specs() {
        return tools.values().stream()
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
