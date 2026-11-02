package tantalum.shell.conversation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tantalum.value.id.RunId;

/**
 * 对话历史的一页。位置是条目在会话里的序号（第几个条目）：条目只追加不删除，所以位置稳定；
 * 不用事件序号，因为排队的提问会夹在上一个运行的条目中间，序号和顺序不单调。
 * 一页从 {@code before} 往前取，只在不会把运行劈开的地方切开，直到至少 {@value #MIN_MESSAGES} 条提问和回复、
 * 跨 {@value #MIN_RUNS} 个运行，最多 {@value #MAX_ITEMS} 个条目。单个运行超过上限时只能在它中间切。
 */
public record MessagePage(int start, List<TranscriptItem> items, boolean hasMore) {

    public static final int MIN_MESSAGES = 50;
    public static final int MIN_RUNS = 2;
    public static final int MAX_ITEMS = 500;

    public MessagePage {
        items = List.copyOf(items);
    }

    /** {@code before} 为 null 时是最近一页；超出范围时按会话末尾算。 */
    public static MessagePage of(List<TranscriptItem> all, Integer before) {
        int end = before == null ? all.size() : Math.max(0, Math.min(before, all.size()));
        boolean[] cuttable = cuttable(all, end);
        int start = end;
        int messages = 0;
        Set<RunId> runs = new HashSet<>();
        while (start > 0) {
            int cut = start - 1;
            while (!cuttable[cut]) {
                cut--;
            }
            if (end - cut > MAX_ITEMS) {
                if (start == end) {
                    start = end - MAX_ITEMS;
                }
                break;
            }
            for (int i = cut; i < start; i++) {
                TranscriptItem item = all.get(i);
                if (item instanceof TranscriptItem.UserMessage || item instanceof TranscriptItem.Text) {
                    messages++;
                }
                if (item.run() != null) {
                    runs.add(item.run());
                }
            }
            start = cut;
            if (messages >= MIN_MESSAGES && runs.size() >= MIN_RUNS) {
                break;
            }
        }
        return new MessagePage(start, all.subList(start, end), start > 0);
    }

    /** 位置 i 能不能切：没有哪个运行的条目一部分在 i 之前、一部分在 i 及之后。位置 0 总能切。 */
    private static boolean[] cuttable(List<TranscriptItem> all, int end) {
        Map<RunId, Integer> last = new HashMap<>();
        for (int i = 0; i < end; i++) {
            if (all.get(i).run() != null) {
                last.put(all.get(i).run(), i);
            }
        }
        boolean[] cuttable = new boolean[Math.max(end, 1)];
        int furthest = -1;
        for (int i = 0; i < end; i++) {
            cuttable[i] = furthest < i;
            if (all.get(i).run() != null) {
                furthest = Math.max(furthest, last.get(all.get(i).run()));
            }
        }
        return cuttable;
    }
}
