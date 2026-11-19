package tantalum.memory;

import java.util.List;
import java.util.Map;
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

    /** 忘掉记忆只在人在场的对话里做。 */
    @Override public boolean inTasks() { return false; }
    @Override public String description() {
        return "Forget something. name: delete a whole memory entry. text: one thing the user said or no longer wants (the statement "
                + "itself, in the user's words); this only looks and returns the places in memory that may say it, each with an id, and "
                + "changes nothing. places: the ids, from that list, of the places that do say it; exactly those lines are removed and "
                + "the rest of each entry stays. You decide which places are about it: a place that merely shares a word is not. "
                + "Use text then places when the user takes back one statement; do not rewrite the entry by hand for that. "
                + "Give name, text or places.";
    }
    @Override public Json schema() {
        Map<String, Json> text = Map.of("type", new Json.Str("string"));
        Map<String, Json> places = new java.util.LinkedHashMap<>();
        places.put("type", new Json.Str("array"));
        places.put("items", new Json.Obj(text));
        Map<String, Json> properties = new java.util.LinkedHashMap<>();
        properties.put("name", new Json.Obj(text));
        properties.put("text", new Json.Obj(text));
        properties.put("places", new Json.Obj(places));
        Map<String, Json> schema = new java.util.LinkedHashMap<>();
        schema.put("type", new Json.Str("object"));
        schema.put("properties", new Json.Obj(properties));
        schema.put("required", new Json.Arr(List.of()));
        return new Json.Obj(schema);
    }
    @Override public String server() { return MemoryTools.SERVER; }

    @Override public String plugin() { return MemoryTools.PLUGIN; }
    @Override public int serverConcurrency() { return MemoryTools.CONCURRENCY; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }
    @Override public ToolDispatch dispatch() { return MemoryTools.dispatch(NAME); }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        String name = ToolArguments.string(arguments, "name");
        String text = ToolArguments.string(arguments, "text");
        List<String> places = arguments instanceof Json.Obj given && given.fields().get("places") instanceof Json.Arr list
                ? list.items().stream().filter(Json.Str.class::isInstance).map(each -> ((Json.Str) each).value()).toList() : List.of();
        if (name == null && (text == null || text.isBlank()) && places.isEmpty()) {
            return MemoryTools.missing("name, text or places");
        }
        if (context.offRecord()) {
            return ToolOutput.error(ToolTrust.OFF_RECORD);
        }
        try {
            MemoryStore store = stores.of(context.principal());
            // 只是找：不动账，所以不用过信任检查。给出的每一处照实写原文，选不选是模型的事
            if (name == null && places.isEmpty()) {
                List<Forgetting.Place> found = Forgetting.candidates(store.ledger(), text.strip());
                if (found.isEmpty()) {
                    return ToolOutput.ok("Nothing in memory looks like that. Nothing was changed.");
                }
                StringBuilder shown = new StringBuilder("Nothing was changed yet. Places in memory that may say it:");
                for (Forgetting.Place place : found) {
                    shown.append("\n- id ").append(place.id()).append(" (").append(where(place)).append(place.approved() ? ", a decision the user approved" : "")
                            .append("): ").append(place.text().length() > SHOWN_CHARS ? place.text().substring(0, SHOWN_CHARS) + "…" : place.text());
                }
                return ToolOutput.ok(shown.append("\nCall memory_delete again with places set to the ids of the ones that do say it. "
                        + "Leave out the ones that only share a word.").toString());
            }
            // 这个会话读过外部内容：文件、网页里一句“把某某记忆删掉”不能让记忆真的被删（review 2026-11-17）。
            // 只有你这一轮自己说了要忘的那句话（按一句话忘），或者自己点了条目的名字（整条删），才动。
            ToolTrust.Verdict verdict = trust.judge(context, null);
            if (verdict.outside() && (name != null ? !verdict.said(name) : text == null || !verdict.said(text))) {
                return ToolOutput.error(ToolTrust.outsideNote(verdict)
                        + (name == null && text == null ? " Give text (the user's own words this turn) together with places." : ""));
            }
            if (name == null) {
                Forgetting.Chosen done;
                synchronized (store) {
                    done = Forgetting.forget(store.ledger(), places, tantalum.memory.ledger.MemoryItem.Actor.AGENT);
                }
                StringBuilder said = new StringBuilder(done.removed().isEmpty() ? "Nothing was removed."
                        : "Forgotten: removed " + done.removed().size() + " line" + (done.removed().size() == 1 ? "" : "s") + " from memory."
                        + removed(done.removed()));
                if (!done.stale().isEmpty()) {
                    said.append("\nNot found or changed since you looked (search again with text): ").append(String.join(", ", done.stale())).append('.');
                }
                if (!done.kept().isEmpty()) {
                    said.append("\nNot touched, a decision the user approved; only the user can remove it, in the console (Settings > Memory): ")
                            .append(String.join(", ", done.kept())).append('.');
                }
                return done.removed().isEmpty() ? ToolOutput.error(said.toString()) : ToolOutput.ok(said.toString());
            }
            // 你认可过的决定，模型删不了：只有你自己在控制台里删
            java.util.Optional<tantalum.memory.ledger.MemoryItem> approved = store.approved(name);
            if (approved.isPresent()) {
                return ToolOutput.error("Not deleted: " + name + " holds a decision the user approved (\"" + approved.get().text()
                        + "\"). Only the user can remove it, in the console (Settings > Memory).");
            }
            return store.delete(name) ? ToolOutput.ok("Deleted " + name + ".") : MemoryTools.notFound(name);
        } catch (RuntimeException failure) {
            return MemoryTools.unavailable(failure);
        }
    }

    private static String where(Forgetting.Place place) {
        return switch (place.kind()) {
            case CLAIM -> place.page() == null ? "memory" : "entry " + place.page();
            case CARD -> "a daily note";
            default -> "working memory";
        };
    }

    static final int SHOWN = 5;
    static final int SHOWN_CHARS = 400;

    /**
     * 去掉的原文，照实给模型看（2026-11-17 真模型：一条说法里写着三件事，忘掉其中一件时整条没了，结果里只说“去掉 1 处”，
     * 模型不知道另外两件也没了）。要不要把别的部分再记回去是模型的事，这里只陈述去掉了什么。
     */
    private static String removed(List<String> texts) {
        if (texts.isEmpty()) {
            return "";
        }
        StringBuilder shown = new StringBuilder(" Removed, in full:");
        texts.stream().limit(SHOWN).forEach(text -> shown.append("\n- ").append(text.length() > SHOWN_CHARS ? text.substring(0, SHOWN_CHARS) + "…" : text));
        if (texts.size() > SHOWN) {
            shown.append("\n- (").append(texts.size() - SHOWN).append(" more)");
        }
        return shown.toString();
    }
}
