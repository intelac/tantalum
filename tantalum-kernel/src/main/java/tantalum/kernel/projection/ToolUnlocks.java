package tantalum.kernel.projection;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tantalum.value.model.ToolSpec;
import tantalum.value.model.ToolUnlock;
import tantalum.value.state.SessionContext;

/**
 * 按需出现的工具（browser-on-demand）怎么算，内核和对话页共用。纯函数，回放结果相同。
 * <ul>
 *   <li>{@link #after}：一轮里执行过的工具命中规则的 {@code after}，要加上哪些（还没开的、全集里有的）。</li>
 *   <li>{@link #kept}：重新组装时，这个会话里已经加上过、这次规则里还有的（用户没关），继续开着。</li>
 * </ul>
 */
public final class ToolUnlocks {

    private ToolUnlocks() {}

    /** 执行过 {@code called} 以后要加上的工具，按全集的顺序；没有时为空。会话开着全部工具（旧会话）时也为空。 */
    public static List<String> after(SessionContext context, Collection<String> called) {
        if (context == null || context.activeTools() == null) {
            return List.of();
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (ToolUnlock rule : context.unlockRules()) {
            if (rule.after().stream().anyMatch(called::contains)) {
                wanted.addAll(rule.add());
            }
        }
        return context.tools().stream().map(ToolSpec::name)
                .filter(name -> wanted.contains(name) && !context.toolActive(name)).toList();
    }

    /** {@code seen} 里出现过、又在某条规则的 {@code add} 里的工具。 */
    public static Set<String> kept(List<ToolUnlock> rules, List<String> seen) {
        Set<String> kept = new LinkedHashSet<>();
        if (rules == null) {
            return kept;
        }
        for (ToolUnlock rule : rules) {
            rule.add().stream().filter(seen::contains).forEach(kept::add);
        }
        return kept;
    }
}
