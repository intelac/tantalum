package tantalum.memory.recall;

import java.util.HashSet;
import java.util.Set;

/**
 * 两句话的词重合多少（按 {@link Terms}），不调模型。只剩“忘记”和搜索结果去重在用；判断新说法取代哪条旧说法已经不用它
 * （由模型写明编号，见 {@link tantalum.memory.Replacing}）。
 */
public final class Overlap {

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
}
