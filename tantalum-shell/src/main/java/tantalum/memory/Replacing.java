package tantalum.memory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tantalum.memory.ledger.Ledger;
import tantalum.memory.ledger.MemoryItem;

/**
 * 一条新说法取代哪一条旧说法（memory-final-plan 第 9 节“写入”）。哪一条由模型写明（它给编号），这里只查事实、只执行：
 * 编号在不在、是不是生效的说法、是不是你认可过的。两句话像不像、是不是同一件事，代码不判断
 * （以前按词重合猜，2026-11-17 去掉）。
 * <ul>
 *   <li>旧说法是你认可过的决定：新说法不直接生效，标出冲突等你确认，旧的照旧有效；</li>
 *   <li>否则：新说法生效时（当场生效，或者等你确认之后）旧说法记成被它取代。</li>
 * </ul>
 */
public final class Replacing {

    /** 新说法的属性：它取代哪一条。 */
    public static final String SUPERSEDES = "supersedes";

    private Replacing() {}

    /** 模型给的编号指着的旧说法：生效的说法才算（在哪一页都行）；别的（不存在、已被取代、还没确认、不是说法）当作没给。 */
    public static Optional<MemoryItem> target(Ledger ledger, String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return ledger.get(id.strip()).filter(item -> item.kind() == MemoryItem.Kind.CLAIM && item.live());
    }

    /** 这条旧说法是你认可过的决定：模型取代不了，只能标出冲突等你定。 */
    public static boolean approved(MemoryItem old) {
        return old.status() == MemoryItem.Status.APPROVED;
    }

    /** 给新说法记上它和旧说法的关系：认可过的记成冲突并等你确认，别的记成取代。 */
    public static MemoryItem mark(MemoryItem claim, MemoryItem old) {
        Map<String, String> attrs = new LinkedHashMap<>(claim.attrs());
        if (approved(old)) {
            attrs.put(MemoryStore.CONFLICTS_WITH, old.id());
            return claim.withPendingConfirm(true).withAttrs(attrs);
        }
        attrs.put(SUPERSEDES, old.id());
        return claim.withAttrs(attrs);
    }

    /** 新说法已经写下并且当场生效：把它写明取代的那条旧说法记成被取代。等你确认的不在这里做，确认时由 {@link PendingItems} 做。 */
    public static void apply(Ledger ledger, MemoryItem written) {
        String id = written.attr(SUPERSEDES);
        if (id == null || written.pendingConfirm()) {
            return;
        }
        ledger.get(id).filter(MemoryItem::live).filter(old -> !old.id().equals(written.id()))
                .ifPresent(old -> ledger.put(old.withSupersededBy(written.id()), old.version()));
    }
}
