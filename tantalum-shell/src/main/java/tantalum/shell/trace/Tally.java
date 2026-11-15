package tantalum.shell.trace;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tantalum.shell.trace.TraceRecord.Attempt;
import tantalum.shell.trace.TraceRecord.HookDecision;
import tantalum.shell.trace.TraceRecord.ModelRequest;
import tantalum.shell.trace.TraceRecord.ToolCall;
import tantalum.value.model.TokenUsage;

/**
 * 一步、一个压缩组或一次运行的汇总。口径和统计页签（{@code console/src/stats/metrics.ts}）相同：
 * 模型用时是各次尝试之和，首 token 取最后一次尝试，工具用时是这一步工具时间段的并；运行的模型、工具用时和首 token
 * 只算有请求的编号步骤，不算压缩组。{@code tokens} 含压缩的摘要请求，{@code summaryTokens} 是其中摘要请求的部分。
 * 没有记录的指标为 null，不当成 0。
 */
public record Tally(TokenUsage tokens, TokenUsage summaryTokens, Long context, int requests, int attempts, int retries,
                   Long modelMs, Long ttftMs, int toolCalls, int toolErrors, Long toolMs, List<ToolCount> tools,
                   int hooks, int hookErrors, List<String> models, Long ms) {

    /** 一个工具在这一段里调用了几次、几次没有正常完成（出错、被拦、没发出、结果不明）。 */
    public record ToolCount(String name, int calls, int errors) {}

    public Tally {
        tools = List.copyOf(tools);
        models = List.copyOf(models);
    }

    /** 一步或一个压缩组：{@code members} 是这一组的记录。 */
    static Tally ofGroup(Collection<TraceRecord> members) {
        ModelRequest request = firstRequest(members);
        Counts counts = Counts.of(members);
        return new Tally(counts.tokens, counts.summaryTokens, request == null ? null : context(request), counts.requests,
                counts.attempts, counts.attempts - counts.requests, modelMs(members), request == null ? null : ttft(request),
                counts.toolCalls, counts.toolErrors, toolMs(members), counts.tools(), counts.hooks, counts.hookErrors,
                counts.models(), groupMs(members));
    }

    /**
     * 一次运行：{@code all} 是它的全部记录，{@code counted} 是有请求的编号步骤（按先后），{@code ms} 是运行头的时长。
     */
    static Tally ofRun(Collection<TraceRecord> all, List<Collection<TraceRecord>> counted, Long ms) {
        Counts counts = Counts.of(all);
        Long context = null;
        Long ttft = null;
        Long model = null;
        Long tool = null;
        for (int i = 0; i < counted.size(); i++) {
            Collection<TraceRecord> step = counted.get(i);
            ModelRequest request = firstRequest(step);
            if (i == 0) {
                ttft = ttft(request);
            }
            Long stepContext = context(request);
            if (stepContext != null) {
                context = stepContext;
            }
            model = add(model, modelMs(step));
            tool = add(tool, toolMs(step));
        }
        return new Tally(counts.tokens, counts.summaryTokens, context, counts.requests, counts.attempts,
                counts.attempts - counts.requests, model, ttft, counts.toolCalls, counts.toolErrors, tool, counts.tools(),
                counts.hooks, counts.hookErrors, counts.models(), ms);
    }

    /** 和统计页签的 {@code toolLabel} 相同：MCP 工具显示成 “服务/工具”。 */
    static String toolLabel(ToolCall tool) {
        if (tool.server() == null) {
            return tool.name();
        }
        String prefix = "mcp__" + tool.server() + "__";
        return tool.name().startsWith(prefix) ? tool.server() + "/" + tool.name().substring(prefix.length()) : tool.name();
    }

    static boolean failed(ToolCall tool) {
        return tool.finished() && tool.status() != ToolCall.Status.DONE;
    }

    private static ModelRequest firstRequest(Collection<TraceRecord> members) {
        for (TraceRecord record : members) {
            if (record instanceof ModelRequest request) {
                return request;
            }
        }
        return null;
    }

    private static Long context(ModelRequest request) {
        return request == null || request.usage() == null ? null : request.usage().promptTotal();
    }

    private static Long ttft(ModelRequest request) {
        if (request == null || request.attempts().isEmpty()) {
            return null;
        }
        Attempt last = request.last();
        return span(last.startedAt() != null ? last.startedAt() : last.issuedAt(), last.firstTokenAt());
    }

    /** 这一组请求的各次尝试之和；一次都没记下时为 null。 */
    private static Long modelMs(Collection<TraceRecord> members) {
        Long total = null;
        for (TraceRecord record : members) {
            if (record instanceof ModelRequest request) {
                for (Attempt attempt : request.attempts()) {
                    total = add(total, span(attempt.startedAt() != null ? attempt.startedAt() : attempt.issuedAt(), attempt.endedAt()));
                }
            }
        }
        return total;
    }

    /** 这一组工具时间段的并：并行的不重复计。没开始（被拦、没发出）或没结束的不计；一个都没有时为 null。 */
    private static Long toolMs(Collection<TraceRecord> members) {
        List<long[]> intervals = new ArrayList<>();
        for (TraceRecord record : members) {
            if (record instanceof ToolCall tool) {
                Instant start = tool.startedAt() != null ? tool.startedAt() : tool.issuedAt();
                Instant end = tool.head().end();
                if (start != null && end != null) {
                    long from = start.toEpochMilli();
                    intervals.add(new long[] {from, Math.max(from, end.toEpochMilli())});
                }
            }
        }
        if (intervals.isEmpty()) {
            return null;
        }
        intervals.sort(Comparator.comparingLong(interval -> interval[0]));
        long total = 0;
        long[] current = null;
        for (long[] interval : intervals) {
            if (current == null || interval[0] > current[1]) {
                if (current != null) {
                    total += current[1] - current[0];
                }
                current = interval.clone();
            } else {
                current[1] = Math.max(current[1], interval[1]);
            }
        }
        return total + current[1] - current[0];
    }

    /** 最早开始到最晚结束；有没结束的记录时为 null。 */
    private static Long groupMs(Collection<TraceRecord> members) {
        Instant first = null;
        Instant last = null;
        for (TraceRecord record : members) {
            Instant end = record.head().end();
            if (end == null) {
                return null;
            }
            first = first == null || record.head().at().isBefore(first) ? record.head().at() : first;
            last = last == null || end.isAfter(last) ? end : last;
        }
        return first == null ? null : Math.max(0, Duration.between(first, last).toMillis());
    }

    static Long span(Instant from, Instant to) {
        return from == null || to == null ? null : Math.max(0, Duration.between(from, to).toMillis());
    }

    private static Long add(Long total, Long value) {
        if (value == null) {
            return total;
        }
        return total == null ? value : Long.valueOf(total + value);
    }

    /** 只靠数数和相加的那些指标。 */
    private static final class Counts {
        TokenUsage tokens = TokenUsage.ZERO;
        TokenUsage summaryTokens = TokenUsage.ZERO;
        int requests;
        int attempts;
        int toolCalls;
        int toolErrors;
        int hooks;
        int hookErrors;
        final Map<String, int[]> perTool = new LinkedHashMap<>();
        final Set<String> models = new LinkedHashSet<>();

        static Counts of(Collection<TraceRecord> records) {
            Counts counts = new Counts();
            for (TraceRecord record : records) {
                switch (record) {
                    case ModelRequest request -> {
                        counts.requests++;
                        counts.attempts += request.attempts().size();
                        if (request.usage() != null) {
                            counts.tokens = counts.tokens.plus(request.usage());
                            if (request.purpose() == ModelRequest.Purpose.SUMMARY) {
                                counts.summaryTokens = counts.summaryTokens.plus(request.usage());
                            }
                        }
                        String model = model(request);
                        if (model != null) {
                            counts.models.add(model);
                        }
                    }
                    case ToolCall tool -> {
                        counts.toolCalls++;
                        int[] entry = counts.perTool.computeIfAbsent(toolLabel(tool), name -> new int[2]);
                        entry[0]++;
                        if (failed(tool)) {
                            counts.toolErrors++;
                            entry[1]++;
                        }
                    }
                    case HookDecision hook -> {
                        counts.hooks++;
                        if (hook.result() == HookDecision.Result.ERROR) {
                            counts.hookErrors++;
                        }
                    }
                    default -> { }
                }
            }
            return counts;
        }

        List<ToolCount> tools() {
            List<ToolCount> list = new ArrayList<>();
            perTool.forEach((name, entry) -> list.add(new ToolCount(name, entry[0], entry[1])));
            return list;
        }

        List<String> models() {
            return List.copyOf(models);
        }
    }

    /** “供应商/实际模型”；都没记下时为 null。 */
    static String model(ModelRequest request) {
        if (request.actualModel() == null) {
            return request.provider();
        }
        return request.provider() == null ? request.actualModel() : request.provider() + "/" + request.actualModel();
    }
}
