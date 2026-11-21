package tantalum.shell.plugin;

import java.util.EnumSet;
import java.util.Set;
import tantalum.value.state.SessionKind;
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

    /**
     * 属于哪个插件（memory-plugin）：有的话，插件对这个用户关着时这个工具不给模型，开着时再看工具自己的开关。默认不属于插件。
     */
    default String plugin() {
        return null;
    }

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

    /** 这次调用最长能跑多久，等名额的时间也算在内。默认用服务器的标准超时；{@code bash} 这类工具按参数给更长的。 */
    default Duration timeout(Json arguments, Duration standard) {
        return standard;
    }

    /** 调度要的描述：名字、服务端、并发、能不能并行。默认从上面几个方法取；能不靠依赖给出的工具另有静态的一份。 */
    default ToolDispatch dispatch() {
        return ToolDispatch.of(this);
    }

    /**
     * 哪几种会话里有这个工具。默认是普通会话和帮手会话（帮手：一个会话把一件事交代给另一个 agent 去做，为它开的会话）。
     * 只该由人在场的对话来做的事不给帮手：记进这个人的记忆、登记工作区、再把事交代出去。
     * 一个会话照哪种会话拿工具由 {@code SessionProfile.toolsOf} 定（主会话这一步照普通会话）。
     */
    default Set<SessionKind> kinds() {
        return EnumSet.of(SessionKind.REGULAR, SessionKind.HELPER);
    }

    /** 在虚拟线程上执行，要响应中断。抛异常等同于错误结果。 */
    ToolOutput run(ToolContext context, Json arguments) throws Exception;
}
