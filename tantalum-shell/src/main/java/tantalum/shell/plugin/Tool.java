package tantalum.shell.plugin;

import java.time.Duration;
import tantalum.value.json.Json;

/**
 * 工具。{@link #isConcurrencySafe} 只说明调用之间逻辑上能否重叠；服务端能同时处理几个由
 * {@link #serverConcurrency} 决定，默认 1，不假定服务端支持多线程响应。
 */
public interface Tool {

    String name();

    String description();

    /** 参数的 JSON Schema。 */
    Json schema();

    /** 所属服务端。同一服务端的工具共享并发名额，跨运行、跨用户。 */
    default String server() {
        return name();
    }

    default int serverConcurrency() {
        return 1;
    }

    /** 必须是纯函数。默认独占。 */
    default boolean isConcurrencySafe(Json arguments) {
        return false;
    }

    /** 这次调用最长能跑多久，等名额的时间也算在内。默认用服务器的标准超时；{@code bash} 这类工具按参数给更长的（local-workspace L14）。 */
    default Duration timeout(Json arguments, Duration standard) {
        return standard;
    }

    /** 在虚拟线程上执行，要响应中断。抛异常等同于错误结果。 */
    ToolOutput run(ToolContext context, Json arguments) throws Exception;
}
