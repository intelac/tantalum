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
            // 这个会话读过外部内容：文件、网页里一句“把某某记忆删掉”不能让记忆真的被删（review 2026-11-17）。
            // 只有你这一轮自己说了要忘的那句话（按一句话忘），或者自己点了条目的名字（整条删），才动。
            ToolTrust.Verdict verdict = trust.judge(context, null);
            if (verdict.outside() && !verdict.said(name != null ? name : text)) {
                return ToolOutput.error(ToolTrust.outsideNote(verdict));
            }
            // 你认可过的决定，模型删不了：只有你自己在控制台里删
            if (name != null) {
                java.util.Optional<tantalum.memory.ledger.MemoryItem> approved = stores.of(context.principal()).approved(name);
                if (approved.isPresent()) {
                    return ToolOutput.error("Not deleted: " + name + " holds a decision the user approved (\"" + approved.get().text()
                            + "\"). Only the user can remove it, in the console (Settings > Memory).");
                }
            }
            if (name == null) {
                Forgetting.Result result = Forgetting.statement(stores.of(context.principal()), text.strip(),
                        tantalum.memory.ledger.MemoryItem.Actor.AGENT);
                int gone = result.gone();
                String kept = result.kept().isEmpty() ? "" : " Not touched: " + result.kept().size() + " decision"
                        + (result.kept().size() == 1 ? "" : "s") + " the user approved (\"" + result.kept().getFirst().text()
                        + "\"); only the user can remove an approved decision, in the console (Settings > Memory). Tell the user.";
                return ToolOutput.ok((gone == 0 ? "Nothing in memory said that; it will not be noted or brought back from now on."
                        : "Forgotten: removed from " + gone + " place" + (gone == 1 ? "" : "s") + " in memory; it will not be brought back.")
                        + kept);
            }
            return stores.of(context.principal()).delete(name) ? ToolOutput.ok("Deleted " + name + ".") : MemoryTools.notFound(name);
        } catch (RuntimeException failure) {
            return MemoryTools.unavailable(failure);
        }
    }
}
