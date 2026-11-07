package tantalum.shell.memory;

import java.util.List;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolDispatch;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.tool.ToolArguments;
import tantalum.value.json.Json;

/** 新建或同名覆盖一条记忆。校验、凭据检查和上限都在 {@link MemoryStore#write}。 */
public final class MemoryWriteTool implements Tool {

    private final MemoryStores stores;

    public MemoryWriteTool(MemoryStores stores) {
        this.stores = stores;
    }

    static final String NAME = "memory_write";

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "Save a long-term memory for this user (new, or overwrite the entry with the same name). "
                + "name: [a-z0-9-]; type: " + MemoryType.keys() + "; description: one line shown in the index; content: Markdown body.";
    }
    @Override public Json schema() { return ToolArguments.schema(List.of("name", "type", "description", "content"), List.of()); }
    @Override public String server() { return MemoryTools.SERVER; }
    @Override public int serverConcurrency() { return MemoryTools.CONCURRENCY; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }
    @Override public ToolDispatch dispatch() { return MemoryTools.dispatch(NAME); }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        for (String required : List.of("name", "type", "description", "content")) {
            if (ToolArguments.string(arguments, required) == null) {
                return MemoryTools.missing(required);
            }
        }
        String name = ToolArguments.string(arguments, "name");
        try {
            MemoryStore.WriteResult result = stores.of(context.principal()).write(name,
                    ToolArguments.string(arguments, "type"), ToolArguments.string(arguments, "description"),
                    ToolArguments.string(arguments, "content"));
            return switch (result) {
                case MemoryStore.WriteResult.Created created -> ToolOutput.ok("Remembered " + name + ".");
                case MemoryStore.WriteResult.Updated updated -> ToolOutput.ok("Updated " + name + ".");
                case MemoryStore.WriteResult.Rejected rejected -> ToolOutput.error(rejected.reason());
            };
        } catch (RuntimeException failure) {
            return MemoryTools.unavailable(failure);
        }
    }
}
