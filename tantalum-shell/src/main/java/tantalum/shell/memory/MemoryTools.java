package tantalum.shell.memory;

import java.io.UncheckedIOException;
import java.util.List;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;

/** 三个记忆工具共用的部分：服务端名、并发、参数读取、错误文案。工具无状态，身份来自 {@link ToolContext}。 */
public final class MemoryTools {

    static final String SERVER = "memory";
    static final int CONCURRENCY = 16;

    private MemoryTools() {}

    public static List<Tool> all(MemoryStores stores) {
        return List.of(new MemoryWriteTool(stores), new MemoryReadTool(stores), new MemoryDeleteTool(stores));
    }

    static ToolOutput missing(String name) {
        return ToolOutput.error("Missing argument: " + name);
    }

    static ToolOutput notFound(String name) {
        return ToolOutput.error("No memory named " + name + ".");
    }

    /** 存储层的异常不带路径地转给模型。 */
    static ToolOutput unavailable(RuntimeException failure) {
        if (failure instanceof IllegalArgumentException) {
            return ToolOutput.error("Memory unavailable: " + failure.getMessage());
        }
        if (failure instanceof UncheckedIOException) {
            return ToolOutput.error("Memory unavailable: " + failure.getCause().getClass().getSimpleName());
        }
        throw failure;
    }
}
