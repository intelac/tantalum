package tantalum.shell.store;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tantalum.shell.trace.TraceJson;
import tantalum.shell.trace.TraceRecord;
import tantalum.value.id.RunId;
import tools.jackson.databind.json.JsonMapper;

/**
 * 轨迹的一页：整运行地取，从新到旧。{@code seq} 是重放到的最后序号，客户端用它和推送合并；{@code before} 是这一页最早运行的开始序号，
 * 下一页带上它；没有运行时为空。记录按 {@code pos} 排序。
 */
public record TracePage(long seq, List<TraceRecord> records, Long before, boolean hasMore) {

    /** 一页至少这么多运行或记录，满足其一就停；至少一个运行。 */
    static final int RUNS = 20;
    static final int RECORDS = 400;

    public TracePage {
        records = List.copyOf(records);
    }

    /** 开始序号小于 {@code before} 的运行里最新的一页；{@code before} 为空时取最新一页。 */
    public static TracePage of(long seq, Collection<TraceRecord> records, Long before) {
        List<List<TraceRecord>> runs = newestFirst(records, before == null ? Long.MAX_VALUE : before);
        List<TraceRecord> page = new ArrayList<>();
        int taken = 0;
        while (taken < runs.size() && (taken == 0 || (taken < RUNS && page.size() < RECORDS))) {
            page.addAll(runs.get(taken++));
        }
        return page(seq, page, runs.subList(0, taken), taken < runs.size());
    }

    /** 从最新一直到包含 {@code run} 的一页（旧链接定位）；会话里没有这个运行时为空。 */
    static TracePage through(long seq, Collection<TraceRecord> records, RunId run) {
        List<List<TraceRecord>> runs = newestFirst(records, Long.MAX_VALUE);
        List<TraceRecord> page = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            page.addAll(runs.get(i));
            if (runs.get(i).getFirst().head().run().equals(run)) {
                return page(seq, page, runs.subList(0, i + 1), i + 1 < runs.size());
            }
        }
        return null;
    }

    /** 接口格式：{@code seq}、{@code records}（{@link TraceJson}）、{@code before}（没有运行时不写）、{@code hasMore}。 */
    public Map<String, Object> toMap(JsonMapper mapper) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("seq", seq);
        response.put("records", records.stream().map(record -> TraceJson.toMap(mapper, record)).toList());
        response.put("before", before);
        response.put("hasMore", hasMore);
        return response;
    }

    private static TracePage page(long seq, List<TraceRecord> records, List<List<TraceRecord>> runs, boolean hasMore) {
        records.sort(Comparator.comparingLong((TraceRecord record) -> position(record)[0])
                .thenComparingLong(record -> position(record)[1]));
        Long before = runs.isEmpty() ? null : runs.getLast().getFirst().head().run().seq();
        return new TracePage(seq, records, before, hasMore);
    }

    /** 按运行分组，运行按开始序号从新到旧，只留开始序号小于 {@code before} 的。 */
    private static List<List<TraceRecord>> newestFirst(Collection<TraceRecord> records, long before) {
        Map<Long, List<TraceRecord>> byRun = new TreeMap<>(Comparator.reverseOrder());
        for (TraceRecord record : records) {
            long start = record.head().run().seq();
            if (start < before) {
                byRun.computeIfAbsent(start, key -> new ArrayList<>()).add(record);
            }
        }
        return new ArrayList<>(byRun.values());
    }

    private static long[] position(TraceRecord record) {
        String pos = record.head().pos();
        int dot = pos.indexOf('.');
        return new long[] {Long.parseLong(pos.substring(0, dot)), Long.parseLong(pos.substring(dot + 1))};
    }
}
