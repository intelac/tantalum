package tantalum.shell.memory;

import java.util.List;
import java.util.Optional;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.tool.ToolArguments;
import tantalum.value.json.Json;

/** 读一条记忆的摘要和正文。索引常驻 system，所以这里只按名字读，不做搜索。 */
public final class MemoryReadTool implements Tool {

    private final MemoryStores stores;

    public MemoryReadTool(MemoryStores stores) {
        this.stores = stores;
    }

    @Override public String name() { return "memory_read"; }
    @Override public String description() { return "Read the full body of one of your memory entries by name (see the memory index)."; }
    @Override public Json schema() { return ToolArguments.schema(List.of("name"), List.of()); }
    @Override public String server() { return MemoryTools.SERVER; }
    @Override public int serverConcurrency() { return MemoryTools.CONCURRENCY; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        String name = ToolArguments.string(arguments, "name");
        if (name == null) {
            return MemoryTools.missing("name");
        }
        try {
            Optional<MemoryEntry> entry = stores.of(context.principal()).read(name);
            if (entry.isEmpty()) {
                return MemoryTools.notFound(name);
            }
            MemoryEntry found = entry.get();
            return ToolOutput.ok("name: " + found.name() + "\ntype: " + found.type().key() + "\ndescription: " + found.description()
                    + "\nupdated: " + found.updatedAt() + "\n\n" + found.content());
        } catch (RuntimeException failure) {
            return MemoryTools.unavailable(failure);
        }
    }
}
