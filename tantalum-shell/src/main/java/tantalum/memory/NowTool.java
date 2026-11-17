package tantalum.memory;

import java.util.LinkedHashMap;
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
 * 工作记忆的工具（memory-final-plan 第 5 节）：看（list）、加（add）、改（update，带版本）、了结（close）。
 * 加的时候带上你的原话（quote）就算你亲口说的；这一轮读过外部内容又没有原话的，等你确认。
 */
public final class NowTool implements Tool {

    static final String NAME = "now";
    private static final Map<String, MemoryItem.Kind> KINDS = Map.of("task", MemoryItem.Kind.TASK, "commitment", MemoryItem.Kind.COMMITMENT,
            "waiting", MemoryItem.Kind.WAITING, "noticed", MemoryItem.Kind.NOTICED);

    private final MemoryStores stores;
    private final ToolTrust trust;

    public NowTool(MemoryStores stores, ToolTrust trust) {
        this.stores = stores;
        this.trust = trust;
    }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "Your working memory for this user: what you are doing (task), what was promised (commitment), what you are waiting "
                + "for (waiting), what you noticed (noticed). action: list | add (kind, text; optional next, to, due, on, and quote: the "
                + "user's exact words if they asked for it) | update (id, version; any of text, status, next, to, due, on) | close "
                + "(id, version; status done or dropped). Status is active, waiting, done or dropped. A stale version is refused with "
                + "the current one; read it and try again.";
    }
    @Override public Json schema() {
        return ToolArguments.schema(List.of("action"), List.of("kind", "text", "id", "version", "status", "next", "to", "due", "on", "quote"));
    }
    @Override public String server() { return MemoryTools.SERVER; }
    @Override public String plugin() { return MemoryTools.PLUGIN; }
    @Override public int serverConcurrency() { return MemoryTools.CONCURRENCY; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }
    @Override public ToolDispatch dispatch() { return MemoryTools.dispatch(NAME); }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        String action = ToolArguments.string(arguments, "action");
        if (action == null) {
            return MemoryTools.missing("action");
        }
        try {
            WorkingMemory working = stores.working(context.principal());
            if (context.offRecord()) {
                return ToolOutput.error(ToolTrust.OFF_RECORD);
            }
            if (!action.equals("list") && trust.delegated(context)) {
                return ToolOutput.error(ToolTrust.CHILD);
            }
            if (action.equals("list")) {
                String text = NowText.render(working, stores.of(context.principal()), working.ledger().clock().instant());
                return ToolOutput.ok(text == null ? "Working memory is empty." : text);
            }
            if (context.offRecord()) {
                return ToolOutput.error(ToolTrust.OFF_RECORD);
            }
            return switch (action) {
                case "add" -> add(context, working, arguments);
                case "update", "close" -> update(context, working, arguments, action.equals("close"));
                default -> ToolOutput.error("Unknown action: " + action + ". Use list, add, update or close.");
            };
        } catch (RuntimeException failure) {
            return MemoryTools.unavailable(failure);
        }
    }

    private ToolOutput add(ToolContext context, WorkingMemory working, Json arguments) {
        MemoryItem.Kind kind = KINDS.get(String.valueOf(ToolArguments.string(arguments, "kind")));
        String text = ToolArguments.string(arguments, "text");
        if (kind == null) {
            return ToolOutput.error("kind must be one of task, commitment, waiting, noticed");
        }
        if (text == null || text.isBlank()) {
            return MemoryTools.missing("text");
        }
        Optional<String> sensitive = tantalum.shell.plugin.SensitiveText.current().detect(text);
        if (sensitive.isPresent()) {
            return ToolOutput.error(CredentialGuard.REASON);
        }
        ToolTrust.Verdict verdict = trust.judge(context, text, ToolArguments.string(arguments, "quote"));
        Map<String, String> attrs = details(arguments);
        attrs.put(WorkingMemory.SESSION, context.session().value());
        Optional<MemoryItem> added = working.add(kind, text, attrs, verdict.source(), MemoryItem.Actor.AGENT,
                ToolArguments.string(arguments, "quote"), new MemoryItem.Provenance(context.session().value(),
                        context.run() == null ? null : context.run().value(), context.command() == null ? null : context.command().value(), null),
                verdict.pendingConfirm(), null);
        if (added.isEmpty()) {
            return ToolOutput.ok("Already in working memory.");
        }
        return ToolOutput.ok("Added [" + added.get().id() + " v" + added.get().version() + "]." + (verdict.pendingConfirm() ? ToolTrust.pendingNote(verdict) : ""));
    }

    private ToolOutput update(ToolContext context, WorkingMemory working, Json arguments, boolean close) {
        String id = ToolArguments.string(arguments, "id");
        Integer version = ToolArguments.integer(arguments, "version");
        if (id == null) {
            return MemoryTools.missing("id");
        }
        if (version == null) {
            return MemoryTools.missing("version");
        }
        Map<String, String> changes = details(arguments);
        String status = ToolArguments.string(arguments, "status");
        if (close) {
            status = status == null ? "done" : status;
            if (!status.equals("done") && !status.equals("dropped")) {
                return ToolOutput.error("close takes status done or dropped");
            }
        }
        if (status != null) {
            if (!WorkingMemory.STATUSES.contains(status)) {
                return ToolOutput.error("status must be one of active, waiting, done, dropped");
            }
            changes.put(WorkingMemory.STATUS, status);
        }
        String text = ToolArguments.string(arguments, "text");
        if (text != null && tantalum.shell.plugin.SensitiveText.current().detect(text).isPresent()) {
            return ToolOutput.error(CredentialGuard.REASON);
        }
        // 下一步、等谁、期限这些也是存下来、每轮进上下文的：一样不存密钥
        if (changes.values().stream().anyMatch(value -> value != null && tantalum.shell.plugin.SensitiveText.current().detect(value).isPresent())) {
            return ToolOutput.error(CredentialGuard.REASON);
        }
        // 改写一项的文字：这个会话读过外部内容的话，新的那句得是你这一轮自己说的才改（原来那句是你说的，不能被文件里的话
        // 悄悄换掉）；改状态不受影响
        if (text != null) {
            ToolTrust.Verdict verdict = trust.judge(context, text, ToolArguments.string(arguments, "quote"));
            if (verdict.outside() && !verdict.said(text)) {
                return ToolOutput.error(ToolTrust.outsideNote(verdict));
            }
        }
        // 已经关掉的再关一次：什么都不改，说清楚已经关了。不然每次都回“Updated”，本机模型会一直关下去（2026-11-16 端到端测出来的）
        Optional<MemoryItem> current = working.ledger().get(id);
        String closedAs = current.map(item -> item.attr(WorkingMemory.STATUS)).filter(was -> was.equals("done") || was.equals("dropped")).orElse(null);
        if (closedAs != null && text == null && (status == null || status.equals(closedAs)) && changes.size() <= (status == null ? 0 : 1)) {
            return ToolOutput.ok("[" + id + "] is already closed (" + closedAs + "). Nothing changed; there is nothing more to do for it.");
        }
        boolean closing = "done".equals(status) || "dropped".equals(status);
        return switch (working.update(id, version, text, changes, MemoryItem.Actor.AGENT)) {
            case WorkingMemory.Change.Done done -> ToolOutput.ok(closing
                    ? "Closed [" + done.item().id() + "] (" + status + "). It no longer shows as open; nothing more to do for it."
                    : "Updated [" + done.item().id() + " v" + done.item().version() + "].");
            case WorkingMemory.Change.Stale stale -> ToolOutput.error("Version " + version + " is stale; now it is "
                    + NowText.line(stale.current(), working.ledger().clock().instant()));
            case WorkingMemory.Change.Missing missing -> ToolOutput.error("No working memory item " + id + ".");
        };
    }

    private static Map<String, String> details(Json arguments) {
        Map<String, String> attrs = new LinkedHashMap<>();
        for (String key : List.of(WorkingMemory.NEXT, WorkingMemory.TO, WorkingMemory.DUE, WorkingMemory.ON)) {
            String value = ToolArguments.string(arguments, key);
            if (value != null) {
                attrs.put(key, value.strip());
            }
        }
        return attrs;
    }
}
