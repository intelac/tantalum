package tantalum.memory;

import java.util.List;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolArguments;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolDispatch;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;

/** 删除一条记忆。 */
public final class MemoryDeleteTool implements Tool {

    private final MemoryStores stores;
    private final ToolTrust trust;

    public MemoryDeleteTool(MemoryStores stores) {
        this(stores, ToolTrust.none());
    }

    public MemoryDeleteTool(MemoryStores stores, ToolTrust trust) {
        this.stores = stores;
        this.trust = trust;
    }

    static final String NAME = "memory_delete";

    @Override public String name() { return NAME; }
    @Override public String description() { return "Delete one of your memory entries by name, for example when the user asks you to forget something."; }
    @Override public Json schema() { return ToolArguments.schema(List.of("name"), List.of()); }
    @Override public String server() { return MemoryTools.SERVER; }

    @Override public String plugin() { return MemoryTools.PLUGIN; }
    @Override public int serverConcurrency() { return MemoryTools.CONCURRENCY; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }
    @Override public ToolDispatch dispatch() { return MemoryTools.dispatch(NAME); }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        String name = ToolArguments.string(arguments, "name");
        if (name == null) {
            return MemoryTools.missing("name");
        }
        if (context.offRecord()) {
            return ToolOutput.error(ToolTrust.OFF_RECORD);
        }
        if (trust.delegated(context)) {
            return ToolOutput.error(ToolTrust.CHILD);
        }
        try {
            return stores.of(context.principal()).delete(name) ? ToolOutput.ok("Deleted " + name + ".") : MemoryTools.notFound(name);
        } catch (RuntimeException failure) {
            return MemoryTools.unavailable(failure);
        }
    }
}
