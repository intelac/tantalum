package tantalum.memory;

import java.util.List;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolArguments;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolDispatch;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;

/** 新建或同名覆盖一条记忆。校验、凭据检查和上限都在 {@link MemoryStore#write}。 */
public final class MemoryWriteTool implements Tool {

    private final MemoryStores stores;
    private final ToolTrust trust;

    public MemoryWriteTool(MemoryStores stores) {
        this(stores, ToolTrust.none());
    }

    public MemoryWriteTool(MemoryStores stores, ToolTrust trust) {
        this.stores = stores;
        this.trust = trust;
    }

    static final String NAME = "memory_write";

    @Override public String name() { return NAME; }

    /** 写这个人的记忆只在人在场的对话里做：任务里说的话不是用户说的。 */
    @Override public boolean inTasks() { return false; }
    @Override public String description() {
        return "Save a long-term memory for this user (new, or overwrite the entry with the same name). "
                + "name: [a-z0-9-]; type: " + MemoryType.keys() + "; description: one line shown in the index; content: Markdown body.";
    }
    @Override public Json schema() { return ToolArguments.schema(List.of("name", "type", "description", "content"), List.of()); }
    @Override public String server() { return MemoryTools.SERVER; }

    @Override public String plugin() { return MemoryTools.PLUGIN; }
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
        if (context.offRecord()) {
            return ToolOutput.error(ToolTrust.OFF_RECORD);
        }
        try {
            MemoryStore store = stores.of(context.principal());
            String type = ToolArguments.string(arguments, "type");
            String description = ToolArguments.string(arguments, "description");
            String content = ToolArguments.string(arguments, "content");
            // memory-final-plan 第 7 节：读过外部内容的一轮里写的等你确认；第 4 节：你认可过的决定只能由你改
            ToolTrust.Verdict verdict = trust.judge(context, null);
            java.util.Optional<tantalum.memory.ledger.MemoryItem> approved = store.approved(name);
            // 读过外部内容的会话里，写的内容是你这一轮自己说的（词对得上）就照常写，不用等确认
            boolean waits = verdict.pendingConfirm() && !verdict.said(content);
            if (waits || approved.isPresent()) {
                MemoryStore.WriteResult result = store.writePending(name, type, description, content, verdict.source(),
                        new tantalum.memory.ledger.MemoryItem.Provenance(context.session().value(),
                                context.run() == null ? null : context.run().value(), context.command() == null ? null : context.command().value(),
                                null), approved.map(tantalum.memory.ledger.MemoryItem::id).orElse(null));
                if (result instanceof MemoryStore.WriteResult.Rejected rejected) {
                    return ToolOutput.error(rejected.reason());
                }
                return ToolOutput.ok(approved.isPresent()
                        ? "Not changed: " + name + " holds a decision the user approved (\"" + approved.get().text() + "\"). Your version was "
                                + "saved for the user to confirm; ask them whether they really want to change it."
                        : "Saved " + name + " for confirmation." + ToolTrust.pendingNote(verdict));
            }
            MemoryStore.WriteResult result = store.write(name, type, description, content);
            return switch (result) {
                case MemoryStore.WriteResult.Created created -> ToolOutput.ok("Remembered " + name + ".");
                case MemoryStore.WriteResult.Updated updated -> ToolOutput.ok("Updated " + name + ".");
                case MemoryStore.WriteResult.Pending pending -> ToolOutput.ok("Saved " + name + " for confirmation.");
                case MemoryStore.WriteResult.Rejected rejected -> ToolOutput.error(rejected.reason());
            };
        } catch (RuntimeException failure) {
            return MemoryTools.unavailable(failure);
        }
    }
}
