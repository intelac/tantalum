package tantalum.shell.plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import tantalum.kernel.tool.ToolSafety;
import tantalum.value.json.Json;

/**
 * 调度一次工具调用要知道的事（review-fixes 需求 12.3）：名字、所属服务端和它的并发上限、这次调用能不能和别的重叠。
 * 和工具的实现分开：检查工具（{@code check}）只要回答调度问题，用它就不必打开附件库、连电脑、起出口。
 *
 * @param concurrencySafe 必须是参数的纯函数；抛异常或返回 false 都按独占处理
 */
public record ToolDispatch(String name, String server, int serverConcurrency, Predicate<Json> concurrencySafe) {

    public ToolDispatch {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(concurrencySafe, "concurrencySafe");
        if (serverConcurrency < 1) {
            throw new IllegalArgumentException("serverConcurrency of " + name + " must be at least 1");
        }
    }

    /** 永远可以并行（参数的纯函数）。 */
    public static ToolDispatch parallel(String name, String server, int serverConcurrency) {
        return new ToolDispatch(name, server, serverConcurrency, arguments -> true);
    }

    /** 永远独占。 */
    public static ToolDispatch exclusive(String name, String server, int serverConcurrency) {
        return new ToolDispatch(name, server, serverConcurrency, arguments -> false);
    }

    /** 从工具的方法取（实现没有单独给出描述时）。 */
    public static ToolDispatch of(Tool tool) {
        return new ToolDispatch(tool.name(), tool.server(), tool.serverConcurrency(), tool::isConcurrencySafe);
    }

    /** 按名字回答“能不能并行”；没登记的工具按独占。 */
    public static ToolSafety safety(List<ToolDispatch> dispatches) {
        Map<String, Predicate<Json>> byName = new HashMap<>();
        for (ToolDispatch dispatch : dispatches) {
            byName.put(dispatch.name(), dispatch.concurrencySafe());
        }
        return (name, arguments) -> {
            Predicate<Json> safe = byName.get(name);
            return safe != null && safe.test(arguments);
        };
    }
}
