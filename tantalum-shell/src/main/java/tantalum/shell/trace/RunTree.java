package tantalum.shell.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.shell.trace.TraceRecord.Attempt;
import tantalum.shell.trace.TraceRecord.Compacted;
import tantalum.shell.trace.TraceRecord.ContextChange;
import tantalum.shell.trace.TraceRecord.HookDecision;
import tantalum.shell.trace.TraceRecord.ModelRequest;
import tantalum.shell.trace.TraceRecord.PluginWork;
import tantalum.shell.trace.TraceRecord.Question;
import tantalum.shell.trace.TraceRecord.RunHead;
import tantalum.shell.trace.TraceRecord.SystemPrompt;
import tantalum.shell.trace.TraceRecord.ToolCall;
import tantalum.shell.trace.TraceRecord.UserInput;
import tantalum.value.id.RunId;
import tantalum.value.state.EndReason;

/**
 * 一次运行的调用树，从这次运行的轨迹记录算出，不做 I/O。根是运行；下面按先后是编号步骤、压缩组和不属于任何一组的记录；
 * 步骤下面是这一步的记录，请求下面是它的各次尝试。节点只有结构、时刻、状态和数字，不放提示词、回复、参数和结果。
 * 分组和统计页签相同：有步号的归那一步，否则有压缩组的归那个组。
 */
public record RunTree(Node root) {

    /** 节点种类。{@code COMPACTED} 是压缩结果那条记录，{@code COMPACTION} 是它所在的组。 */
    public enum Kind { RUN, STEP, COMPACTION, INPUT, CONTEXT, SYSTEM, COMPACTED, REQUEST, ATTEMPT, TOOL, HOOK, WORK, QUESTION }

    public enum State { OK, ERROR, RUNNING, WAITING, CANCELLED }

    /**
     * {@code id} 是轨迹记录的 ID（尝试是 “请求 ID#序号”，组是 “运行头 ID/s步号” 或 “运行头 ID/c压缩组”）；
     * {@code record} 是去轨迹接口取正文用的记录 ID，组节点为 null；{@code detail} 是错误原因，最多 200 个字符；
     * {@code tally} 只在运行、步骤和压缩组上有。
     */
    public record Node(String id, String parent, Kind kind, String name, Instant start, Instant end, Long ms, State state,
                       String detail, String record, Tally tally, List<Node> children) {

        public Node {
            children = List.copyOf(children);
        }
    }

    static final int DETAIL_CHARS = 200;

    /** {@code records} 只能是这次运行的；没有运行头（只加载了一部分）时根节点的时刻取最早的记录。 */
    public static RunTree of(RunId run, Collection<TraceRecord> records) {
        List<TraceRecord> sorted = new ArrayList<>(records);
        sorted.sort(Comparator.comparing(TraceRecord::head, (a, b) -> comparePos(a.pos(), b.pos())));
        RunHead head = null;
        Map<String, List<TraceRecord>> groups = new LinkedHashMap<>();
        List<Object> order = new ArrayList<>();
        for (TraceRecord record : sorted) {
            if (record instanceof RunHead found) {
                head = found;
                continue;
            }
            String key = groupKey(record);
            if (key == null) {
                order.add(record);
            } else {
                groups.computeIfAbsent(key, ignored -> {
                    order.add(key);
                    return new ArrayList<>();
                }).add(record);
            }
        }
        String rootId = head != null ? head.id() : run.value();
        // 组按请求行的位置排，和统计页签一样；不属于组的记录按自己的位置
        order.sort(Comparator.comparing(item -> item instanceof String key ? anchor(groups.get(key)) : ((TraceRecord) item).head().pos(),
                RunTree::comparePos));
        List<Node> children = new ArrayList<>();
        List<Collection<TraceRecord>> counted = new ArrayList<>();
        for (Object item : order) {
            if (item instanceof String key) {
                List<TraceRecord> members = groups.get(key);
                children.add(group(rootId, key, members));
                if (key.startsWith("s") && members.stream().anyMatch(ModelRequest.class::isInstance)) {
                    counted.add(members);
                }
            } else {
                children.add(leaf(rootId, (TraceRecord) item));
            }
        }
        Instant start = head != null ? head.head().at() : sorted.isEmpty() ? null : sorted.getFirst().head().at();
        Instant end = head != null ? head.head().end() : null;
        Long ms = head == null ? null : Tally.span(start, end);
        Tally tally = Tally.ofRun(records, counted, ms);
        State state = head == null ? State.OK : runState(head, children);
        String detail = head == null ? null : cut(head.endDetail());
        String name = head == null ? null : head.trigger().name();
        return new RunTree(new Node(rootId, null, Kind.RUN, name, start, end, ms, state, detail, head == null ? null : head.id(), tally,
                children));
    }

    private static String groupKey(TraceRecord record) {
        if (record.head().step() != null) {
            return "s" + record.head().step();
        }
        return record.head().compaction() != null ? "c" + record.head().compaction() : null;
    }

    private static String anchor(List<TraceRecord> members) {
        for (TraceRecord record : members) {
            if (record instanceof ModelRequest) {
                return record.head().pos();
            }
        }
        return members.getFirst().head().pos();
    }

    private static Node group(String rootId, String key, List<TraceRecord> members) {
        boolean step = key.startsWith("s");
        String id = rootId + "/" + key;
        List<Node> children = members.stream().map(record -> leaf(id, record)).toList();
        Instant start = members.stream().map(record -> record.head().at()).min(Instant::compareTo).orElse(null);
        Tally tally = Tally.ofGroup(members);
        Instant end = tally.ms() == null ? null : start.plusMillis(tally.ms());
        String name = step ? "step " + key.substring(1) : "compaction";
        return new Node(id, rootId, step ? Kind.STEP : Kind.COMPACTION, name, start, end, tally.ms(), groupState(children), null, null,
                tally, children);
    }

    private static Node leaf(String parent, TraceRecord record) {
        Instant start = record.head().at();
        Instant end = record.head().end();
        List<Node> children = List.of();
        Kind kind;
        String name;
        State state = end == null ? State.RUNNING : State.OK;
        String detail = null;
        switch (record) {
            case UserInput input -> {
                kind = Kind.INPUT;
                name = input.mode().name();
            }
            case ContextChange ignored -> {
                kind = Kind.CONTEXT;
                name = "context";
            }
            case SystemPrompt ignored -> {
                kind = Kind.SYSTEM;
                name = "system";
            }
            case Compacted compacted -> {
                kind = Kind.COMPACTED;
                name = compacted.trigger() == null ? "compacted" : compacted.trigger().name();
                detail = cut(compacted.reason());
            }
            case ModelRequest request -> {
                kind = Kind.REQUEST;
                name = Tally.model(request);
                state = switch (request.status()) {
                    case WAITING -> State.RUNNING;
                    case DONE -> State.OK;
                    case FAILED -> State.ERROR;
                    case INTERRUPTED -> State.CANCELLED;
                };
                detail = cut(request.failure());
                List<Node> attempts = new ArrayList<>();
                for (int i = 0; i < request.attempts().size(); i++) {
                    attempts.add(attempt(request.id(), i + 1, request.attempts().get(i)));
                }
                children = attempts;
            }
            case ToolCall tool -> {
                kind = Kind.TOOL;
                name = Tally.toolLabel(tool);
                // 真正开始执行的时刻：等批准的那段不算进工具
                start = tool.startedAt() != null ? tool.startedAt() : tool.issuedAt() != null ? tool.issuedAt() : start;
                state = switch (tool.status()) {
                    case WAITING -> State.WAITING;
                    case RUNNING -> State.RUNNING;
                    case DONE -> State.OK;
                    case ERROR, BLOCKED, NOT_SENT, UNKNOWN -> State.ERROR;
                };
                if (Tally.failed(tool)) {
                    String why = tool.code() == null ? tool.reason() : tool.reason() == null ? tool.code() : tool.code() + ": " + tool.reason();
                    detail = cut(why != null ? why : tool.status().name());
                }
            }
            case HookDecision hook -> {
                kind = Kind.HOOK;
                name = hook.hook();
                state = hook.result() == HookDecision.Result.ERROR ? State.ERROR : State.OK;
                detail = hook.result() == HookDecision.Result.ERROR || hook.result() == HookDecision.Result.STOP ? cut(hook.reason()) : null;
            }
            case PluginWork work -> {
                kind = Kind.WORK;
                name = work.workType();
                start = work.startedAt() != null ? work.startedAt() : start;
                state = switch (work.status()) {
                    case RUNNING -> State.RUNNING;
                    case DONE -> State.OK;
                    case FAILED -> State.ERROR;
                    case INTERRUPTED -> State.CANCELLED;
                };
            }
            case Question question -> {
                kind = Kind.QUESTION;
                name = question.askedBy();
                state = switch (question.status()) {
                    case WAITING -> State.WAITING;
                    case ANSWERED -> State.OK;
                    case EXPIRED, CANCELLED -> State.CANCELLED;
                };
            }
            case RunHead ignored -> throw new IllegalArgumentException("a run head is the root, not a leaf");
        }
        return new Node(record.id(), parent, kind, name, start, end, Tally.span(start, end), state, detail, record.id(), null, children);
    }

    private static Node attempt(String request, int number, Attempt attempt) {
        Instant start = attempt.startedAt() != null ? attempt.startedAt() : attempt.issuedAt();
        State state = switch (attempt.outcome()) {
            case PENDING -> State.RUNNING;
            case REPLIED -> State.OK;
            case FAILED -> State.ERROR;
            case INTERRUPTED -> State.CANCELLED;
        };
        String detail = attempt.reason() == null ? null : cut(attempt.reason().name() + (attempt.detail() == null || attempt.detail().isEmpty()
                ? "" : ": " + attempt.detail()));
        return new Node(request + "#" + number, request, Kind.ATTEMPT, "attempt " + number, start, attempt.endedAt(),
                Tally.span(start, attempt.endedAt()), state, detail, request, null, List.of());
    }

    /** 有没结束的就是进行中（有在等人的就是等待中），否则有出错的就是出错。 */
    private static State groupState(List<Node> children) {
        boolean running = false;
        boolean error = false;
        for (Node child : children) {
            switch (child.state()) {
                case WAITING -> {
                    return State.WAITING;
                }
                case RUNNING -> running = true;
                case ERROR -> error = true;
                default -> { }
            }
        }
        return running ? State.RUNNING : error ? State.ERROR : State.OK;
    }

    private static State runState(RunHead head, List<Node> children) {
        EndReason reason = head.endReason();
        if (reason == null) {
            return children.stream().anyMatch(child -> child.state() == State.WAITING) ? State.WAITING : State.RUNNING;
        }
        return switch (reason) {
            case COMPLETED -> State.OK;
            case FAILED -> State.ERROR;
            case CANCELLED, STOPPED -> State.CANCELLED;
        };
    }

    static String cut(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= DETAIL_CHARS ? text : text.substring(0, DETAIL_CHARS);
    }

    /** “序号.下标”，和客户端的 {@code comparePos} 相同。 */
    static int comparePos(String a, String b) {
        int dotA = a.indexOf('.');
        int dotB = b.indexOf('.');
        long seqA = Long.parseLong(dotA < 0 ? a : a.substring(0, dotA));
        long seqB = Long.parseLong(dotB < 0 ? b : b.substring(0, dotB));
        if (seqA != seqB) {
            return Long.compare(seqA, seqB);
        }
        int indexA = dotA < 0 ? 0 : Integer.parseInt(a.substring(dotA + 1));
        int indexB = dotB < 0 ? 0 : Integer.parseInt(b.substring(dotB + 1));
        return Integer.compare(indexA, indexB);
    }
}
