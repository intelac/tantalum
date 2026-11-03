package tantalum.shell.trace;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tantalum.shell.trace.TraceRecord.ModelRequest.Purpose;
import tantalum.shell.trace.TraceRecord.Segment;
import tantalum.shell.trace.TraceRecord.Source;
import tantalum.shell.trace.TraceRecord.UserInput.Mode;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.decision.Decision;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.KernelState;

/**
 * 事件到轨迹记录的增量投影，按会话保存。输入是 {@code (事件, 处理前状态, 内核决定)}，实时和回放走同一个函数，所以结果相同。
 * 定下来的记录不留在内存里，只留编号、上一次请求的 system 和工具、未结束的运行和还会变的记录。只改内存，不做 I/O。
 */
public final class Trace {

    /** 一次请求 system 和工具的来源：system 按段，工具按个。 */
    public record Split(List<Segment> system, List<Source> tools) {

        public Split {
            system = List.copyOf(system);
            tools = List.copyOf(tools);
        }
    }

    /** 排队中的提交，运行开始时变成用户记录。 */
    public record Queued(long seq, Instant at, String text, Mode mode, List<AttachmentRef> attachments) {}

    /** 一个会话的状态。 */
    static final class Book {
        int requests;
        int turns;
        TokenUsage total = TokenUsage.ZERO;
        /** 上一次请求的有效 system 和工具，判断这次变了没有。 */
        String system;
        List<ToolSpec> tools;
        final Map<RunId, Track> runs = new LinkedHashMap<>();
        /** 还会变的记录：等待中的请求、未结束的工具和工作、运行标题。定下来就丢。 */
        final Map<String, TraceRecord> open = new LinkedHashMap<>();
        final Deque<Queued> queued = new ArrayDeque<>();
        long lastSeq = -1;
    }

    /** 一个未结束的运行。 */
    static final class Track {
        Integer turn;
        boolean manual;
        /** 最近一个正常请求的步号，0 表示还没有请求。 */
        int step;
        /** 正在进行的压缩组，没有时为空。 */
        String compaction;
        Purpose lastPurpose;
        int lastRetries;
        String lastRequest;
        /** 最近一次调用的来源切分；摘要重试不重跑钩子，沿用上一次摘要调用的切分。 */
        Split split;
        Split summarySplit;
        /** 请求前的记录，先归下一步；接下来是同一请求的重试时改归当前步。 */
        final Map<String, TraceRecord> pending = new LinkedHashMap<>();
    }

    /** 一个运行的状态，见 {@link Track}。 */
    public record RunSaved(RunId run, Integer turn, boolean manual, int step, String compaction, Purpose lastPurpose, int lastRetries,
                           String lastRequest, Split split, Split summarySplit, List<TraceRecord> pending) {

        public RunSaved {
            pending = List.copyOf(pending);
        }
    }

    /** 一个会话的完整内部状态，重放会话文件得到，恢复后接着处理事件和没中断时相同。 */
    public record Saved(int requests, int turns, TokenUsage total, String system, List<ToolSpec> tools, List<RunSaved> runs,
                        List<TraceRecord> open, List<Queued> queued, long lastSeq) {

        public Saved {
            runs = List.copyOf(runs);
            open = List.copyOf(open);
            queued = List.copyOf(queued);
        }
    }

    private final Map<SessionId, Book> sessions = new ConcurrentHashMap<>();

    /** 内核处理完一个事件后调用。返回这次变化的记录，按第一次变化的先后排列；同一记录改了几次只返回最终版本。 */
    public List<TraceRecord> apply(EventEnvelope envelope, KernelState before, Decision decision) {
        Book book = sessions.computeIfAbsent(envelope.session(), key -> new Book());
        Map<String, TraceRecord> changed = new LinkedHashMap<>();
        synchronized (book) {
            new TracePass(book, envelope, before, decision, changed).run();
            book.lastSeq = envelope.seq();
        }
        return List.copyOf(changed.values());
    }

    /** 某个运行最近一次模型调用的来源切分（请求详情用）；运行已结束或没有调用时为空。 */
    public Split lastSplit(SessionId session, RunId run) {
        Book book = sessions.get(session);
        if (book == null) {
            return null;
        }
        synchronized (book) {
            Track track = book.runs.get(run);
            return track == null ? null : track.split;
        }
    }

    /** 没有该会话时返回空的状态。 */
    public Saved export(SessionId session) {
        Book book = sessions.get(session);
        if (book == null) {
            return new Saved(0, 0, TokenUsage.ZERO, null, null, List.of(), List.of(), List.of(), -1);
        }
        synchronized (book) {
            List<RunSaved> runs = new ArrayList<>();
            book.runs.forEach((run, track) -> runs.add(new RunSaved(run, track.turn, track.manual, track.step, track.compaction,
                    track.lastPurpose, track.lastRetries, track.lastRequest, track.split, track.summarySplit,
                    List.copyOf(track.pending.values()))));
            return new Saved(book.requests, book.turns, book.total, book.system, book.tools, runs, List.copyOf(book.open.values()),
                    List.copyOf(book.queued), book.lastSeq);
        }
    }

    /** {@code saved} 为空时当作新会话。 */
    public void restore(SessionId session, Saved saved) {
        Book book = new Book();
        if (saved != null) {
            book.requests = saved.requests();
            book.turns = saved.turns();
            book.total = saved.total();
            book.system = saved.system();
            book.tools = saved.tools();
            for (RunSaved run : saved.runs()) {
                Track track = new Track();
                track.turn = run.turn();
                track.manual = run.manual();
                track.step = run.step();
                track.compaction = run.compaction();
                track.lastPurpose = run.lastPurpose();
                track.lastRetries = run.lastRetries();
                track.lastRequest = run.lastRequest();
                track.split = run.split();
                track.summarySplit = run.summarySplit();
                run.pending().forEach(record -> track.pending.put(record.id(), record));
                book.runs.put(run.run(), track);
            }
            saved.open().forEach(record -> book.open.put(record.id(), record));
            book.queued.addAll(saved.queued());
            book.lastSeq = saved.lastSeq();
        }
        sessions.put(session, book);
    }

    public void remove(SessionId session) {
        sessions.remove(session);
    }
}
