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
    @Override public String description() {
        return "Forget something. name: delete a whole memory entry. text: forget one thing the user said or no longer wants (give the "
                + "statement itself, in the user's words); it is removed from every entry, note and working memory and is not brought "
                + "back later, while the rest of those entries stays. Use text when the user takes back one statement; do not rewrite "
                + "the entry by hand for that. Give name or text.";
    }
    @Override public Json schema() { return ToolArguments.schema(List.of(), List.of("name", "text")); }
    @Override public String server() { return MemoryTools.SERVER; }

    @Override public String plugin() { return MemoryTools.PLUGIN; }
    @Override public int serverConcurrency() { return MemoryTools.CONCURRENCY; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }
    @Override public ToolDispatch dispatch() { return MemoryTools.dispatch(NAME); }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        String name = ToolArguments.string(arguments, "name");
        String text = ToolArguments.string(arguments, "text");
        if (name == null && (text == null || text.isBlank())) {
            return MemoryTools.missing("name or text");
        }
        if (context.offRecord()) {
            return ToolOutput.error(ToolTrust.OFF_RECORD);
        }
        if (trust.delegated(context)) {
            return ToolOutput.error(ToolTrust.CHILD);
        }
        try {
            if (name == null) {
                int gone = Forgetting.statement(stores.of(context.principal()), text.strip(), tantalum.memory.ledger.MemoryItem.Actor.AGENT);
                return ToolOutput.ok(gone == 0 ? "Nothing in memory said that; it will not be noted or brought back from now on."
                        : "Forgotten: removed from " + gone + " place" + (gone == 1 ? "" : "s") + " in memory; it will not be brought back.");
            }
            return stores.of(context.principal()).delete(name) ? ToolOutput.ok("Deleted " + name + ".") : MemoryTools.notFound(name);
        } catch (RuntimeException failure) {
            return MemoryTools.unavailable(failure);
        }
    }
}
