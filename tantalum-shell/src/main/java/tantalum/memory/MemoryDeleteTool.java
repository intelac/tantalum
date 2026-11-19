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

    /** 平台的服务：在过去的对话里找候选、把选中的撤下；拿不到（测试里）时只管记忆账。 */
    private final java.util.function.Supplier<tantalum.shell.plugin.PluginServices> platform;

    public MemoryDeleteTool(MemoryStores stores) {
        this(stores, ToolTrust.none(), () -> null);
    }

    public MemoryDeleteTool(MemoryStores stores, ToolTrust trust, java.util.function.Supplier<tantalum.shell.plugin.PluginServices> platform) {
        this.stores = stores;
        this.trust = trust;
        this.platform = platform;
    }

    static final String NAME = "memory_delete";

    @Override public String name() { return NAME; }

    /** 忘掉记忆只在人在场的对话里做。 */
    @Override public boolean inTasks() { return false; }
    @Override public String description() {
        return "Forget something. name: delete a whole memory entry. text: one thing the user said or no longer wants (the statement "
                + "itself, in the user's words); this only looks and returns the places that may say it (in memory, and in past "
                + "conversations where it was said), each with an id, and changes nothing. places: the ids, from that list, of the places "
                + "that do say it; exactly those lines are removed from memory or withdrawn from the conversation, and the rest stays. You decide which places are about it: a place that merely shares a word is not. "
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
                List<Talk.Line> said = Talk.candidates(platform.get(), context.principal(), text.strip());
                if (found.isEmpty() && said.isEmpty()) {
                    return ToolOutput.ok("Nothing in memory or in past conversations looks like that. Nothing was changed.");
                }
                StringBuilder shown = new StringBuilder("Nothing was changed yet. Places that may say it:");
                for (Forgetting.Place place : found) {
                    shown.append("\n- id ").append(place.id()).append(" (").append(where(place)).append(place.approved() ? ", a decision the user approved" : "")
                            .append("): ").append(cut(place.text()));
                }
                for (Talk.Line line : said) {
                    shown.append("\n- id ").append(line.id()).append(" (a past conversation, said by ")
                            .append(line.role().equals("user") ? "the user" : "the assistant").append("): ").append(cut(line.text()));
                }
                // 一处是整行去掉的，去掉以后这一行在这个会话里也看不到了：行里还写着别的要留的事，得让模型先另记下来
                return ToolOutput.ok(shown.append("\nCall memory_delete again with places set to the ids of the ones that do say it. "
                        + "Leave out the ones that only share a word. A place goes as a whole line and will no longer be shown: if a line "
                        + "also says other things worth keeping, note those again first, then forget the line.").toString());
            }
            // 这个会话读过外部内容：文件、网页里一句“把某某记忆删掉”不能让记忆真的被删（review 2026-11-17）。
            // 只有你这一轮自己说了要忘的那句话（按一句话忘），或者自己点了条目的名字（整条删），才动。
            ToolTrust.Verdict verdict = trust.judge(context, null);
            if (verdict.outside() && (name != null ? !verdict.said(name) : text == null || !verdict.said(text))) {
                return ToolOutput.error(ToolTrust.outsideNote(verdict)
                        + (name == null && text == null ? " Give text (the user's own words this turn) together with places." : ""));
            }
            if (name == null) {
                // 先撤对话里的、再动记忆账：中途断了，账里的候选还在，重来一遍就行（撤下重复送没有害处）
                Talk.Done talk = Talk.withdraw(platform.get(), context.principal(), places, context);
                List<String> lines = places.stream().filter(place -> !Talk.is(place)).toList();
                Forgetting.Chosen done = new Forgetting.Chosen(List.of(), List.of(), List.of());
                if (!lines.isEmpty()) {
                    synchronized (store) {
                        done = Forgetting.forget(store.ledger(), lines, tantalum.memory.ledger.MemoryItem.Actor.AGENT);
                    }
                }
                int followed = Talk.follow(platform.get(), context.principal(), done.removed(), context);
                // 结果里不再复述去掉的原文：这条结果自己也会留在会话里
                StringBuilder said = new StringBuilder();
                if (!done.removed().isEmpty()) {
                    said.append("Forgotten: removed ").append(done.removed().size()).append(done.removed().size() == 1 ? " line" : " lines")
                            .append(" from memory, exactly the ones you chose.");
                }
                if (talk.withdrawn() + followed > 0) {
                    said.append(said.isEmpty() ? "" : "\n").append("Withdrawn from conversations: ").append(talk.withdrawn() + followed)
                            .append(talk.withdrawn() + followed == 1 ? " line" : " lines")
                            .append(" (shown as " + tantalum.shell.plugin.PluginServices.WITHDRAWN + " from now on).");
                }
                if (said.isEmpty()) {
                    said.append("Nothing was removed.");
                } else {
                    said.append("\nDo not repeat what was forgotten in your reply.");
                }
                List<String> stale = new java.util.ArrayList<>(done.stale());
                stale.addAll(talk.stale());
                if (!stale.isEmpty()) {
                    said.append("\nNot found or changed since you looked (search again with text): ").append(String.join(", ", stale)).append('.');
                }
                if (!done.kept().isEmpty()) {
                    said.append("\nNot touched, a decision the user approved; only the user can remove it, in the console (Settings > Memory): ")
                            .append(String.join(", ", done.kept())).append('.');
                }
                return done.removed().isEmpty() && talk.withdrawn() == 0 ? ToolOutput.error(said.toString()) : ToolOutput.ok(said.toString());
            }
            // 你认可过的决定，模型删不了：只有你自己在控制台里删
            java.util.Optional<tantalum.memory.ledger.MemoryItem> approved = store.approved(name);
            if (approved.isPresent()) {
                return ToolOutput.error("Not deleted: " + name + " holds a decision the user approved (\"" + approved.get().text()
                        + "\"). Only the user can remove it, in the console (Settings > Memory).");
            }
            List<String> lines = store.read(name).map(entry -> entry.content().lines().map(String::strip).filter(line -> !line.isBlank()).toList())
                    .orElse(List.of());
            if (!store.delete(name)) {
                return MemoryTools.notFound(name);
            }
            // 条目里的行在对话里逐字出现的地方跟着撤下（事实）；别处换了说法复述它的不猜，列出来让模型再选
            Talk.follow(platform.get(), context.principal(), lines, context);
            StringBuilder said = new StringBuilder("Deleted " + name + ".");
            java.util.Map<String, String> others = new java.util.LinkedHashMap<>();
            for (String line : lines.subList(0, Math.min(3, lines.size()))) {
                Forgetting.candidates(store.ledger(), line).forEach(place -> others.putIfAbsent(place.id(), "(" + where(place) + "): " + cut(place.text())));
                Talk.candidates(platform.get(), context.principal(), line).forEach(place -> others.putIfAbsent(place.id(),
                        "(a past conversation, said by " + (place.role().equals("user") ? "the user" : "the assistant") + "): " + cut(place.text())));
            }
            if (!others.isEmpty()) {
                said.append(" Other places may still say the same; nothing else was changed:");
                others.entrySet().stream().limit(Forgetting.MAX_PLACES).forEach(each -> said.append("\n- id ").append(each.getKey()).append(' ').append(each.getValue()));
                said.append("\nIf the user wants it forgotten everywhere, call memory_delete with places set to the ids of the ones that do say it.");
            }
            return ToolOutput.ok(said.toString());
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

    static final int SHOWN_CHARS = 400;

    private static String cut(String text) {
        return text.length() > SHOWN_CHARS ? text.substring(0, SHOWN_CHARS) + "…" : text;
    }
}
