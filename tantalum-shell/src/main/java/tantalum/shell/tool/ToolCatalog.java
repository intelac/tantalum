package tantalum.shell.tool;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tantalum.kernel.tool.ToolSafety;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolDispatch;
import tantalum.value.model.ToolSpec;

/**
 * 已注册的工具。同一服务端的工具必须声明相同的并发上限。
 * <p>以前还有运行中变化的工具来源（服务器端的 MCP、用户电脑上的本机 MCP），remove-generic-mcp 去掉了：要用某个 MCP 服务器，
 * 写一个插件注册固定的工具，像浏览器那样（工具定义固定，调用时由插件转给它包着的 MCP）。工具列表从而只随开关变，不随连接变。
 */
public final class ToolCatalog {

    private final Map<String, Tool> tools = new LinkedHashMap<>();
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
        tools.put(tool.name(), tool);
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 按名称排序，工具顺序不随注册的先后变化，缓存前缀才稳定。 */
    public List<ToolSpec> specs() {
        return tools().stream().map(tool -> new ToolSpec(tool.name(), tool.description(), tool.schema())).toList();
    }

    /** 全部工具，按名称排序（同 {@link #specs()}）。 */
    public List<Tool> tools() {
        return tools.values().stream().sorted(Comparator.comparing(Tool::name)).toList();
    }

    /** 给内核用的判断：登记的工具的调度规则；没登记的按独占，工具抛的异常由内核按独占处理。 */
    public ToolSafety safety() {
        return ToolDispatch.safety(tools.values().stream().map(Tool::dispatch).toList());
    }
}
