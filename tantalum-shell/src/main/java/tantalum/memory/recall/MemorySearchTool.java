package tantalum.memory.recall;

import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;
import tantalum.memory.MemoryStores;
import tantalum.memory.MemoryTools;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolArguments;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolDispatch;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;

/**
 * {@code memory_search}（memory-final-plan 第 9 节第 5 步）：在这个人的记忆里按关键词找页面上的说法和当天卡，带出处。
 * 范围同自动找回：个人的加当前目录的项目；被取代的不给，没确认的标出来。换说法、换语言多找几次。
 */
public final class MemorySearchTool implements Tool {

    public static final String NAME = "memory_search";

    private final MemoryStores stores;
    private final Function<Principal, ZoneId> zones;
    private final Semantic semantic;

    public MemorySearchTool(MemoryStores stores, Function<Principal, ZoneId> zones, Semantic semantic) {
        this.stores = stores;
        this.zones = zones;
        this.semantic = semantic;
    }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "Search this user's memory (entries and daily notes) by keywords and, where the server has it, by meaning; results show where each came from. Words like "
                + "'three days ago' or '上周' narrow the dates. If nothing is found, try other words or another language before saying "
                + "you do not know. When the result says more notes match, pass offset to read on: do that before you count or list everything.";
    }
    @Override public Json schema() {
        return ToolArguments.schema(List.of(ToolArguments.Param.required("query", "string", "What to look for."),
                ToolArguments.Param.optional("offset", "integer", "Skip this many results: the number the previous result told you to use.")));
    }
    @Override public String server() { return MemoryTools.SERVER; }
    @Override public String plugin() { return MemoryTools.PLUGIN; }
    @Override public int serverConcurrency() { return 16; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }
    @Override public ToolDispatch dispatch() { return ToolDispatch.parallel(NAME, MemoryTools.SERVER, 16); }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        String query = ToolArguments.string(arguments, "query");
        if (query == null || query.isBlank()) {
            return ToolOutput.error("Missing argument: query");
        }
        java.util.Optional<String> spent = stores.budget().spend(context);
        if (spent.isPresent()) {
            return ToolOutput.ok(spent.get());
        }
        if (context.offRecord()) {
            return ToolOutput.error("This conversation is off the record: memory is not read or written.");
        }
        Integer offset = ToolArguments.integer(arguments, "offset");
        ZoneId zone = zones.apply(context.principal());
        Recall.Found found = new Recall(stores.ledger(context.principal())).find(query, "", context.dir(), zone,
                semantic.near(context.principal(), query), Recall.SEARCH.from(offset == null ? 0 : offset));
        String text = RecallText.of(found, zone, true);
        return ToolOutput.ok(text == null ? (offset != null && offset > 0 ? "No more notes match \"" + query + "\" after the first " + offset + "."
                : "Nothing in memory matches \"" + query + "\". Try other words or another language.") : text);
    }
}
