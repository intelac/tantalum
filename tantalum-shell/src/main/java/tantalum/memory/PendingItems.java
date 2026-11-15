package tantalum.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import tantalum.memory.ledger.Ledger;
import tantalum.memory.ledger.MemoryItem;

/**
 * 等你确认的条目（memory-final-plan 第 4 节 {@code pending_confirm}、第 7 节）：列出、确认（生效；整条改写的取代页面上原来的说法）、
 * 撤销（撤掉，不再出现）。确认和撤销只能由你在控制台做，模型做不了。
 */
public final class PendingItems {

    /** 结果：做了、本来就是这样（重复确认）、没有这一条。 */
    public enum Outcome { DONE, ALREADY, MISSING }

    private final Ledger ledger;

    public PendingItems(Ledger ledger) {
        this.ledger = ledger;
    }

    /** 全部等确认的，新的在前。 */
    public List<MemoryItem> list() {
        List<MemoryItem> all = new ArrayList<>(ledger.items(item -> item.state() == MemoryItem.State.CONFIRMED && item.pendingConfirm()
                && item.supersededBy() == null && item.kind() != MemoryItem.Kind.PAGE));
        all.sort(Comparator.comparing(MemoryItem::created).reversed());
        return all;
    }

    public synchronized Outcome confirm(String id) {
        Optional<MemoryItem> found = ledger.get(id).filter(item -> item.kind() != MemoryItem.Kind.PAGE);
        if (found.isEmpty() || found.get().state() == MemoryItem.State.WITHDRAWN) {
            return Outcome.MISSING;
        }
        MemoryItem item = found.get();
        if (!item.pendingConfirm()) {
            return Outcome.ALREADY;
        }
        MemoryItem confirmed = item.withPendingConfirm(false).withActor(MemoryItem.Actor.USER).withLastConfirmed(ledger.clock().instant());
        if (!(ledger.put(confirmed, item.version()) instanceof Ledger.Result.Written)) {
            return Outcome.MISSING;
        }
        if (item.kind() == MemoryItem.Kind.CLAIM && item.page() != null) {
            for (MemoryItem page : ledger.items(other -> other.kind() == MemoryItem.Kind.PAGE && item.page().equals(other.page())
                    && other.pendingConfirm() && other.state() == MemoryItem.State.CONFIRMED)) {
                ledger.put(page.withPendingConfirm(false).withActor(MemoryItem.Actor.USER), page.version());
            }
            if ("page".equals(item.attr(MemoryStore.REPLACES))) {
                for (MemoryItem old : ledger.items(other -> other.kind() == MemoryItem.Kind.CLAIM && item.page().equals(other.page())
                        && other.live() && !other.id().equals(item.id()))) {
                    ledger.put(old.withSupersededBy(item.id()), old.version());
                }
            }
        }
        return Outcome.DONE;
    }

    public synchronized Outcome withdraw(String id) {
        Optional<MemoryItem> found = ledger.get(id).filter(item -> item.kind() != MemoryItem.Kind.PAGE);
        if (found.isEmpty()) {
            return Outcome.MISSING;
        }
        MemoryItem item = found.get();
        if (item.state() == MemoryItem.State.WITHDRAWN) {
            return Outcome.ALREADY;
        }
        if (!item.pendingConfirm()) {
            return Outcome.MISSING;
        }
        ledger.put(item.withState(MemoryItem.State.WITHDRAWN).withActor(MemoryItem.Actor.USER), item.version());
        if (item.kind() == MemoryItem.Kind.CLAIM && item.page() != null) {
            boolean others = !ledger.items(other -> other.kind() == MemoryItem.Kind.CLAIM && item.page().equals(other.page())
                    && other.state() == MemoryItem.State.CONFIRMED).isEmpty();
            if (!others) {
                for (MemoryItem page : ledger.items(other -> other.kind() == MemoryItem.Kind.PAGE && item.page().equals(other.page())
                        && other.pendingConfirm() && other.state() == MemoryItem.State.CONFIRMED)) {
                    ledger.put(page.withState(MemoryItem.State.WITHDRAWN).withActor(MemoryItem.Actor.USER), page.version());
                }
            }
        }
        return Outcome.DONE;
    }
}
