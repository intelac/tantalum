package tantalum.memory.recall;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tantalum.memory.ledger.Ledger;
import tantalum.memory.ledger.MemoryItem;

/**
 * 两句话说的是不是同一件事（memory-final-plan 第 9 节“写入”）：按 {@link Terms} 的词重合多少算，不调模型。
 * 用来判断新的偏好取代旧的（重合一半以上），和新的决定碰上你认可过的决定（重合三分之一以上就算冲突，宁可多问你一次）。
 */
public final class Overlap {

    static final double SAME_TOPIC = 0.5;
    static final double CONFLICT = 0.34;

    private Overlap() {}

    /** 重合的词占较短那句的比例；有一句没有词时为 0。 */
    public static double of(String a, String b) {
        Set<String> left = Terms.of(a);
        Set<String> right = Terms.of(b);
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        Set<String> shared = new HashSet<>(left);
        shared.retainAll(right);
        return (double) shared.size() / Math.min(left.size(), right.size());
    }

    /** 和这句新决定冲突的、你认可过的决定（生效的说法里 {@code status = approved}、重合够多的那条）。 */
    public static Optional<MemoryItem> approvedConflict(Ledger ledger, String decision) {
        return ledger.items(item -> item.kind() == MemoryItem.Kind.CLAIM && item.live() && item.status() == MemoryItem.Status.APPROVED)
                .stream().filter(item -> of(item.text(), decision) >= CONFLICT).findFirst();
    }

    /** 这句新偏好要取代的旧偏好（同一页上、生效的、重合一半以上的）。 */
    public static List<MemoryItem> supersededPreferences(Ledger ledger, String page, String preference) {
        return ledger.items(item -> item.kind() == MemoryItem.Kind.CLAIM && item.live() && page.equals(item.page())
                && of(item.text(), preference) >= SAME_TOPIC);
    }
}
