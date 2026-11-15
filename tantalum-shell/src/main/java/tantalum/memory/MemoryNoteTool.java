package tantalum.memory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import tantalum.memory.ledger.MemoryItem;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolArguments;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolDispatch;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;

/**
 * 随手记一条（memory-final-plan 第 9 节的 {@code memory_note}）：偏好、事实、决定进对应的页面，注意到的进工作记忆。
 * 带上你的原话（quote）就算你亲口说的；这一轮读过外部内容又没有原话的，等你确认。
 */
public final class MemoryNoteTool implements Tool {

    static final String NAME = "memory_note";
    private static final Map<String, String[]> SHELVES = Map.of(
            "preference", new String[] {"preferences", "preference", "How the user wants things done"},
            "fact", new String[] {"about-me", "person", "Facts about the user and their work"},
            "decision", new String[] {"decisions", "decision", "Decisions made and why"});

    private final MemoryStores stores;
    private final ToolTrust trust;

    public MemoryNoteTool(MemoryStores stores, ToolTrust trust) {
        this.stores = stores;
        this.trust = trust;
    }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "Note one thing worth remembering for this user. kind: preference | fact | decision | noticed; text: one sentence; "
                + "quote: the user's exact words that show it (needed for it to count as what the user said). Use memory_write instead "
                + "to write or rewrite a whole entry.";
    }
    @Override public Json schema() { return ToolArguments.schema(List.of("kind", "text"), List.of("quote")); }
    @Override public String server() { return MemoryTools.SERVER; }
    @Override public String plugin() { return MemoryTools.PLUGIN; }
    @Override public int serverConcurrency() { return MemoryTools.CONCURRENCY; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }
    @Override public ToolDispatch dispatch() { return MemoryTools.dispatch(NAME); }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        String kind = ToolArguments.string(arguments, "kind");
        String text = ToolArguments.string(arguments, "text");
        if (kind == null) {
            return MemoryTools.missing("kind");
        }
        if (text == null || text.isBlank()) {
            return MemoryTools.missing("text");
        }
        if (context.offRecord()) {
            return ToolOutput.error(ToolTrust.OFF_RECORD);
        }
        String quote = ToolArguments.string(arguments, "quote");
        if (tantalum.shell.plugin.SensitiveText.current().detect(text, quote).isPresent()) {
            return ToolOutput.error(CredentialGuard.REASON);
        }
        try {
            ToolTrust.Verdict verdict = trust.judge(context, quote);
            // 子运行写的记一笔是谁写的（memory-final-plan 第 12 节）
            MemoryItem.Actor actor = trust.delegated(context) ? MemoryItem.Actor.CHILD : MemoryItem.Actor.AGENT;
            MemoryItem.Provenance provenance = new MemoryItem.Provenance(context.session().value(),
                    context.run() == null ? null : context.run().value(), context.command() == null ? null : context.command().value(), null);
            String suffix = verdict.pendingConfirm() ? ToolTrust.pendingNote(verdict) : "";
            if (kind.equals("noticed")) {
                Optional<MemoryItem> added = stores.working(context.principal()).add(MemoryItem.Kind.NOTICED, text,
                        Map.of(WorkingMemory.SESSION, context.session().value()), verdict.source(), actor, quote, provenance,
                        verdict.pendingConfirm(), null);
                return ToolOutput.ok(added.isEmpty() ? "Already noted." : "Noted." + suffix);
            }
            String[] shelf = SHELVES.get(kind);
            if (shelf == null) {
                return ToolOutput.error("kind must be one of preference, fact, decision, noticed");
            }
            MemoryStore store = stores.of(context.principal());
            MemoryItem claim = MemoryItem.create(store.ledger().newId(), MemoryItem.Kind.CLAIM, verdict.source(), actor,
                    text.strip(), store.ledger().clock().instant()).withQuote(quote).withProvenance(provenance)
                    .withPendingConfirm(verdict.pendingConfirm());
            Optional<MemoryItem> added = store.addClaim(shelf[0], shelf[1], shelf[2], claim);
            return ToolOutput.ok(added.isEmpty() ? "Already noted in " + shelf[0] + "." : "Noted in " + shelf[0] + "." + suffix);
        } catch (RuntimeException failure) {
            return MemoryTools.unavailable(failure);
        }
    }
}
