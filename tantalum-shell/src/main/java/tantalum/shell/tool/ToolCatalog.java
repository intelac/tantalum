package tantalum.shell.tool;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import tantalum.kernel.tool.ToolSafety;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolDispatch;
import tantalum.value.model.ToolSpec;

/** 已注册的工具。同一服务端的工具必须声明相同的并发上限。动态工具（如 MCP）来自 {@link ToolSource}，同名时已注册的工具优先。 */
public final class ToolCatalog {

    /** 运行中会变化的工具来源。必须线程安全。 */
    public interface ToolSource {
        List<Tool> tools();

        Optional<Tool> find(String name);

        /** 插件页和开关用的服务器名，即配置里的名字。{@link Tool#server()} 是并发名额的命名空间，可能带前缀。 */
        default String serverName(Tool tool) {
            return tool.server();
        }

        /** 等来源准备好，比如第一次连接外部服务器。有上限，不阻塞内核（在 Handler 的虚拟线程上调用）。 */
        default void awaitReady() throws InterruptedException { }
    }

    private volatile ToolSource source = new ToolSource() {
        @Override public List<Tool> tools() { return List.of(); }
        @Override public Optional<Tool> find(String name) { return Optional.empty(); }
    };

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

    public void awaitReady() throws InterruptedException {
        source.awaitReady();
    }

    public void setSource(ToolSource newSource) {
        source = newSource;
    }

    public Optional<Tool> find(String name) {
        Tool registered = tools.get(name);
        return registered != null ? Optional.of(registered) : source.find(name);
    }

    /** 按名称排序，工具顺序不随注册或连接的先后变化，缓存前缀才稳定。 */
    public List<ToolSpec> specs() {
        return tools().stream().map(tool -> new ToolSpec(tool.name(), tool.description(), tool.schema())).toList();
    }

    /** 已注册的和动态来源的全部工具，按名称排序（同 {@link #specs()}）。 */
    public List<Tool> tools() {
        return Stream.concat(tools.values().stream(), dynamic().stream())
                .sorted(Comparator.comparing(Tool::name)).toList();
    }

    /** 动态来源（MCP）里的工具，和已注册的同名的不算。 */
    public List<Tool> dynamic() {
        return source.tools().stream().filter(tool -> !tools.containsKey(tool.name())).toList();
    }

    /** 动态来源里的工具属于哪个服务器（配置里的名字）。 */
    public String serverName(Tool tool) {
        return source.serverName(tool);
    }

    /** 内置和 Spring 注册的工具；不是的就来自动态来源。 */
    public boolean isRegistered(String name) {
        return tools.containsKey(name);
    }

    /** 给内核用的判断。未注册的工具按独占处理；工具抛的异常由内核按独占处理。 */
    /** 登记的工具的调度规则；没登记的按独占。MCP 工具恒为独占，来源里的不用进来。 */
    public ToolSafety safety() {
        return ToolDispatch.safety(tools.values().stream().map(Tool::dispatch).toList());
    }
}
