package tantalum.memory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tantalum.shell.plugin.ToolContext;

/**
 * 一轮里找记忆的次数（2026-11-16 评测：本机 qwen 找到了答案还接着搜，memory_search、history_search、memory_read
 * 一轮调了 50 到 188 次，直到超时）。{@code memory_search}、{@code history_search}、{@code memory_read} 合计，一轮超过
 * {@value #PER_TURN} 次后不再搜，改回一句“别再搜了，用找到的回答”。不在运行里（测试）不计。每台服务器一份，最多记最近 2000 轮。
 */
public final class SearchBudget {

    public static final int PER_TURN = 8;
    static final String SPENT = "You have already looked in memory and past conversations " + PER_TURN + " times in this turn. "
            + "Stop searching now: answer from what you have found, and say plainly what you could not find.";
    private static final int KEEP = 2000;

    private final Map<String, Integer> counts = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > KEEP;
        }
    };

    /** 记一次；这一轮已经用完时给要回给模型的话，没用完为空。 */
    public synchronized Optional<String> spend(ToolContext context) {
        if (context.run() == null) {
            return Optional.empty();
        }
        String key = context.principal().value() + "\n" + context.session().value() + "\n" + context.run().value();
        int used = counts.merge(key, 1, Integer::sum);
        return used > PER_TURN ? Optional.of(SPENT) : Optional.empty();
    }
}
