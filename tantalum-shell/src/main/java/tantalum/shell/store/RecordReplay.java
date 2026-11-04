package tantalum.shell.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.journal.Line;
import tantalum.shell.journal.RecordMemo;
import tantalum.shell.journal.SessionCorruptException;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.loop.Projections;
import tantalum.shell.loop.SessionSlice;
import tantalum.shell.trace.Trace;
import tantalum.value.decision.Decision;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.KernelState;

/**
 * 读一个会话文件，用结果行折叠出这个会话的内存状态（record-results R4）：每一步把结果行套到处理前的状态上，
 * 得到和当时内核相同的决定，不调内核和钩子。回调和 {@link SessionReplay} 相同，所以投影、索引和查询照常运行。
 * 结果行必须按序回答最早一个还没回答的事件。文件末尾没有结果行的事件是还没处理完的步骤：查询时它们正在途中，跳过；
 * 冷载入时不该有（会话只在全部结果落盘后才离开内存，崩溃留下的由启动时的补写处理），有就拒绝载入。
 */
public final class RecordReplay {

    /** 冷载入的结果，附带读完整个文件后的 memo，交给写入方接着写结果行。 */
    public record Loaded(SessionSlice slice, RecordMemo memo) {}

    /** 折叠完整个文件：状态、文件末尾还没有结果行的事件（按序号）、memo。 */
    record Folded(KernelState state, List<EventEnvelope> unanswered, RecordMemo memo) {}

    private final SessionPaths paths;
    private final SessionFileReader reader;

    public RecordReplay(SessionPaths paths, SessionFileReader reader) {
        this.paths = paths;
        this.reader = reader;
    }

    /** 折叠序号小于 {@code beforeSeq} 的步骤；没有这样的事件时为空。memo 总是读完整个文件。 */
    public Optional<Loaded> load(Principal principal, SessionId session, long beforeSeq) throws IOException {
        Path file = paths.sessionFile(principal, session);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Transcript transcript = new Transcript();
        Trace trace = new Trace();
        Projections projections = new Projections(transcript, trace);
        Fold fold = new Fold(file, beforeSeq, projections::apply);
        RecordMemo memo = reader.forEachLine(file, fold::accept).toWriter();
        fold.requireAnswered();
        if (fold.seq < 0 || fold.state.sessions().get(session) == null) {
            return Optional.empty();
        }
        return Optional.of(new Loaded(new SessionSlice(fold.state.sessions().get(session), List.copyOf(fold.state.runs().values()),
                transcript.export(session), trace.export(session), fold.seq), memo));
    }

    /** 折叠整个文件，留下末尾没有结果行的事件给启动时的补写。 */
    Folded fold(Path file) throws IOException {
        Fold fold = new Fold(file, Long.MAX_VALUE, (envelope, before, decision) -> { });
        RecordMemo memo = reader.forEachLine(file, fold::accept).toWriter();
        return new Folded(fold.state, List.copyOf(fold.waiting.values()), memo);
    }

    /** 按序折叠全部步骤，每一步回调一次（{@code /turns}、{@code /trace}、SSE 补读）。文件不存在时什么也不做。 */
    public void each(Principal principal, Path file, SessionReplay.Step step) throws IOException {
        until(principal, file, Long.MAX_VALUE, step);
    }

    /** 同 {@link #each}，只到序号小于 {@code beforeSeq} 的步骤。 */
    public void until(Principal principal, Path file, long beforeSeq, SessionReplay.Step step) throws IOException {
        reader.forEachLine(file, new Fold(file, beforeSeq, step)::accept);
    }

    /** 按文件顺序配对事件行和结果行：结果行可能落在后面几个事件之后，但同一会话里按序号递增。 */
    private static final class Fold {
        private final Path file;
        private final long beforeSeq;
        private final SessionReplay.Step step;
        private final Map<Long, EventEnvelope> waiting = new LinkedHashMap<>();
        KernelState state = KernelState.empty();
        long seq = -1;

        Fold(Path file, long beforeSeq, SessionReplay.Step step) {
            this.file = file;
            this.beforeSeq = beforeSeq;
            this.step = step;
        }

        void accept(Line line) {
            if (line.seq() >= beforeSeq) {
                return;
            }
            switch (line) {
                case Line.Event event -> waiting.put(event.seq(), event.envelope());
                case Line.Result result -> {
                    Long oldest = waiting.isEmpty() ? null : waiting.keySet().iterator().next();
                    if (oldest == null || oldest != result.seq()) {
                        throw new SessionCorruptException("session file " + file + " answers seq " + result.seq()
                                + " before seq " + oldest, null);
                    }
                    EventEnvelope envelope = waiting.remove(oldest);
                    KernelState before = state;
                    Decision decision = result.record().apply(envelope, before);
                    step.accept(envelope, before, decision);
                    state = decision.newState();
                    seq = result.seq();
                }
            }
        }

        /** 冷载入：折叠到的范围里不能有没处理完的事件，这里不重算。 */
        void requireAnswered() {
            if (!waiting.isEmpty()) {
                List<Long> seqs = new ArrayList<>(waiting.keySet());
                throw new SessionCorruptException("session file " + file + " has events without results: seq " + seqs
                        + "; they are handled when the server starts", null);
            }
        }
    }
}
