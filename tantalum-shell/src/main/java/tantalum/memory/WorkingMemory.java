package tantalum.memory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tantalum.memory.capture.Quotes;
import tantalum.memory.ledger.Ledger;
import tantalum.memory.ledger.MemoryItem;

/**
 * 工作记忆（memory-final-plan 第 5 节）：在做的事、答应的事、在等的事、最近注意到的，都在记忆账上，每项带状态和项目范围。
 * 状态：{@code active}（进行中）、{@code waiting}（等待）、{@code done}（完成）、{@code dropped}（放下）。
 */
public final class WorkingMemory {

    public static final Set<MemoryItem.Kind> KINDS = Set.of(MemoryItem.Kind.TASK, MemoryItem.Kind.COMMITMENT, MemoryItem.Kind.WAITING,
            MemoryItem.Kind.NOTICED);
    public static final String TITLE = "title";
    public static final String STATUS = "status";
    public static final String SESSION = "session";
    public static final String NEXT = "next";
    public static final String DUE = "due";
    public static final String TO = "to";
    public static final String ON = "on";
    public static final String ACTIVE = "active";
    public static final Set<String> OPEN = Set.of("active", "waiting");
    public static final Set<String> STATUSES = Set.of("active", "waiting", "done", "dropped");

    private final Ledger ledger;

    public WorkingMemory(Ledger ledger) {
        this.ledger = ledger;
    }

    public Ledger ledger() {
        return ledger;
    }

    /** 生效的工作记忆，最近改过的在前。 */
    public List<MemoryItem> items() {
        return ledger.items(item -> KINDS.contains(item.kind()) && item.live()).stream()
                .sorted(Comparator.comparing(MemoryItem::updated).reversed()).toList();
    }

    /** 还没了结的（进行中、等待），最近改过的在前。 */
    public List<MemoryItem> open() {
        return items().stream().filter(item -> OPEN.contains(status(item))).toList();
    }

    /** 等你确认的（外部来源），最近的在前；已经了结的（完成、放下）不算。 */
    public List<MemoryItem> pending() {
        return ledger.items(item -> KINDS.contains(item.kind()) && item.state() == MemoryItem.State.CONFIRMED && item.pendingConfirm()
                && item.supersededBy() == null && OPEN.contains(status(item)));
    }

    public static String status(MemoryItem item) {
        String status = item.attr(STATUS);
        return status == null ? ACTIVE : status;
    }

    /**
     * 加一项（捕获和 {@code now} 工具用）。还没了结的里面已经有意思一样的（规范化后相同）就不写。返回写下的；没写为空。
     */
    public synchronized Optional<MemoryItem> add(MemoryItem.Kind kind, String title, Map<String, String> attrs, MemoryItem.Source source,
                                                 MemoryItem.Actor actor, String quote, MemoryItem.Provenance provenance, boolean pendingConfirm,
                                                 String scope) {
        if (!KINDS.contains(kind) || title == null || title.isBlank()) {
            return Optional.empty();
        }
        String same = Quotes.normalize(title);
        boolean duplicate = ledger.items(item -> item.kind() == kind && item.state() == MemoryItem.State.CONFIRMED
                && item.supersededBy() == null && OPEN.contains(status(item)))
                .stream().anyMatch(item -> Quotes.normalize(item.attr(TITLE) == null ? item.text() : item.attr(TITLE)).equals(same));
        if (duplicate) {
            return Optional.empty();
        }
        java.util.LinkedHashMap<String, String> all = new java.util.LinkedHashMap<>();
        all.put(TITLE, title.strip());
        all.put(STATUS, ACTIVE);
        all.putAll(attrs);
        MemoryItem item = MemoryItem.create(ledger.newId(), kind, source, actor, title.strip(), ledger.clock().instant())
                .withAttrs(all).withQuote(quote).withProvenance(provenance).withPendingConfirm(pendingConfirm)
                .withScope(scope == null ? "personal" : scope);
        Ledger.Result written = ledger.put(item, 0);
        return written instanceof Ledger.Result.Written done ? Optional.of(done.item()) : Optional.empty();
    }

    /** 改的结果：写下了（新版本），版本对不上（给现在的样子），或者没有这一项。 */
    public sealed interface Change {
        record Done(MemoryItem item) implements Change {}

        record Stale(MemoryItem current) implements Change {}

        record Missing() implements Change {}
    }

    /** 按版本改一项的标题、状态和其他属性（{@code null} 的不改）。版本对不上时不写，返回现在的样子。 */
    public synchronized Change update(String id, int expectedVersion, String title, Map<String, String> changes, MemoryItem.Actor actor) {
        Optional<MemoryItem> found = ledger.get(id).filter(item -> KINDS.contains(item.kind()) && item.state() == MemoryItem.State.CONFIRMED);
        if (found.isEmpty()) {
            return new Change.Missing();
        }
        MemoryItem item = found.get();
        if (item.version() != expectedVersion) {
            return new Change.Stale(item);
        }
        java.util.LinkedHashMap<String, String> attrs = new java.util.LinkedHashMap<>(item.attrs());
        changes.forEach((key, value) -> {
            if (value != null) {
                attrs.put(key, value);
            }
        });
        MemoryItem next = item.withAttrs(attrs).withActor(actor);
        if (title != null && !title.isBlank()) {
            attrs.put(TITLE, title.strip());
            next = next.withText(title.strip()).withAttrs(attrs);
        }
        Ledger.Result written = ledger.put(next, expectedVersion);
        return switch (written) {
            case Ledger.Result.Written done -> new Change.Done(done.item());
            case Ledger.Result.Conflict conflict -> conflict.current() == null ? new Change.Missing() : new Change.Stale(conflict.current());
        };
    }
}
