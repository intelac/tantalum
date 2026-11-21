package tantalum.shell.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tantalum.shell.loop.Snapshots;
import tantalum.shell.journal.Snapshot;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.journal.Line;
import tantalum.shell.journal.RecordMemo;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.loop.Projections;
import tantalum.shell.loop.SessionSlice;
import tantalum.shell.trace.Trace;
import tantalum.value.decision.Decision;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.KernelState;

/**
 * 读一个会话文件，用每一行记下的结果折叠出这个会话的内存状态：每一步把结果套到处理前的状态上，
 * 得到和当时内核相同的决定，不调内核和钩子。回调和 {@link SessionReplay} 相同，所以投影、索引和查询照常运行。
 * 文件里只有处理完的步骤，没有要配对或补写的。
 */
public final class RecordReplay {

    /** 和 {@link SessionReplay.Step} 相同，另带这一步每次模型调用当时记下的请求指纹（请求详情页核对用）。 */
    @FunctionalInterface
    public interface RecordedStep {
        void accept(EventEnvelope envelope, KernelState before, Decision decision, Map<CommandId, String> fingerprints);
    }

    /**
     * 冷载入的结果，附带读完文件后的 memo，交给写入方接着写结果行。{@code from} 是读起的位置（最后一份快照，没用快照为 0），
     * {@code snapshotBytes} 是那份快照行的大小：写入方从这里量什么时候要下一份。
     */
    public record Loaded(SessionSlice slice, RecordMemo memo, long from, long snapshotBytes) {}


    private final SessionPaths paths;
    private final SessionFileReader reader;
    private final Snapshots snapshots;

    public RecordReplay(SessionPaths paths, SessionFileReader reader, Snapshots snapshots) {
        this.paths = paths;
        this.reader = reader;
        this.snapshots = snapshots;
    }

    /** 冷载入：折叠序号小于 {@code beforeSeq} 的步骤；没有这样的事件时为空。memo 总是读完整个文件。 */
    public Optional<Loaded> load(Principal principal, SessionId session, long beforeSeq) throws IOException {
        return fold(principal, session, beforeSeq);
    }

    /** 不在内存的会话的查询：折叠整个文件。 */
    public Optional<SessionSlice> read(Principal principal, SessionId session) throws IOException {
        return fold(principal, session, Long.MAX_VALUE).map(Loaded::slice);
    }

    /**
     * 从最后一份快照读起（它在 {@code beforeSeq} 之前时）：用快照起头，再套上它后面的步。没有可用的快照就从头读。
     * 从快照读起和从头读起结果一样（快照之后的行只引用快照和它之后的内容）。
     */
    private Optional<Loaded> fold(Principal principal, SessionId session, long beforeSeq) throws IOException {
        Path file = paths.sessionFile(principal, session);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        // 从文件尾往前找：只读最后一份快照之后写的那些字节（写快照的门槛把它们限在几 MiB）
        long from = Math.max(0, SessionFileReader.lastSnapshot(file));
        Optional<Loaded> loaded = fold(principal, session, file, from, beforeSeq);
        // 快照在 beforeSeq 之后（很少：载入的是排在它前面的那一步之前的样子）：从头读
        return loaded.isEmpty() && from > 0 ? fold(principal, session, file, 0, beforeSeq) : loaded;
    }

    private Optional<Loaded> fold(Principal principal, SessionId session, Path file, long from, long beforeSeq) throws IOException {
        Transcript transcript = new Transcript();
        Trace trace = new Trace();
        Projections projections = new Projections(transcript, trace);
        Fold fold = new Fold(beforeSeq, (envelope, before, decision, fingerprints) -> projections.apply(envelope, before, decision));
        long[] started = {0, 0};
        boolean[] tooLate = {false};
        RecordMemo memo = reader.forEachEntry(file, from, new SessionFileReader.Visitor() {
            @Override
            public void step(Line line) {
                fold.accept(line);
            }

            @Override
            public void snapshot(Snapshot snapshot, long offset) {
                if (offset != from) {
                    return;
                }
                if (snapshot.seq() >= beforeSeq) {
                    tooLate[0] = true;
                    return;
                }
                SessionSlice slice = snapshots.slice(snapshot);
                fold.state = KernelState.empty().withResident(slice.session(), slice.runs());
                fold.seq = snapshot.seq();
                projections.restore(session, slice);
                started[0] = offset;
            }
        }).toWriter();
        if (tooLate[0] || fold.seq < 0 || fold.state.sessions().get(session) == null) {
            return Optional.empty();
        }
        if (from > 0) {
            started[1] = snapshotBytes(file, from);
        }
        return Optional.of(new Loaded(new SessionSlice(fold.state.sessions().get(session), List.copyOf(fold.state.runs().values()),
                transcript.export(session), trace.export(session), fold.seq), memo, started[0], started[1]));
    }

    private static long snapshotBytes(Path file, long offset) throws IOException {
        return SessionFileReader.lineLength(file, offset);
    }

    /**
     * 从头读，核对每份快照和前面折叠出来的这个会话（状态、运行、两个投影）一样。返回第一份不一样的快照跟着的那一步的序号；
     * 都一样（或者没有快照）为空。复核用。
     */
    public java.util.OptionalLong firstWrongSnapshot(Principal principal, SessionId session) throws IOException {
        Path file = paths.sessionFile(principal, session);
        Transcript transcript = new Transcript();
        Trace trace = new Trace();
        Projections projections = new Projections(transcript, trace);
        Fold fold = new Fold(Long.MAX_VALUE, (envelope, before, decision, fingerprints) -> projections.apply(envelope, before, decision));
        java.util.OptionalLong[] wrong = {java.util.OptionalLong.empty()};
        reader.forEachEntry(file, 0, new SessionFileReader.Visitor() {
            @Override
            public void step(Line line) {
                fold.accept(line);
            }

            @Override
            public void snapshot(Snapshot snapshot, long offset) {
                if (wrong[0].isPresent()) {
                    return;
                }
                SessionSlice folded = projections.export(fold.state, session, fold.seq);
                SessionSlice written = snapshots.slice(snapshot);
                if (snapshot.seq() != fold.seq || !folded.session().equals(written.session())
                        || !java.util.Set.copyOf(folded.runs()).equals(java.util.Set.copyOf(written.runs()))
                        || !folded.transcript().equals(written.transcript()) || !folded.trace().equals(written.trace())) {
                    wrong[0] = java.util.OptionalLong.of(snapshot.seq());
                }
            }
        });
        return wrong[0];
    }

    /** 按序折叠全部步骤，每一步回调一次（{@code /turns}、{@code /trace}、SSE 补读）。文件不存在时什么也不做。 */
    public void each(Principal principal, Path file, SessionReplay.Step step) throws IOException {
        until(principal, file, Long.MAX_VALUE, step);
    }

    /** 同 {@link #each}，只到序号小于 {@code beforeSeq} 的步骤。 */
    public void until(Principal principal, Path file, long beforeSeq, SessionReplay.Step step) throws IOException {
        until(principal, file, beforeSeq, (RecordedStep) (envelope, before, decision, fingerprints) -> step.accept(envelope, before, decision));
    }

    /** 同上，另带记下的请求指纹。 */
    public void until(Principal principal, Path file, long beforeSeq, RecordedStep step) throws IOException {
        reader.forEachLine(file, new Fold(beforeSeq, step)::accept);
    }

    /** 按文件顺序把每一行的结果套到处理前的状态上。 */
    private static final class Fold {
        private final long beforeSeq;
        private final RecordedStep step;
        KernelState state = KernelState.empty();
        long seq = -1;

        Fold(long beforeSeq, RecordedStep step) {
            this.beforeSeq = beforeSeq;
            this.step = step;
        }

        void accept(Line line) {
            if (line.seq() >= beforeSeq) {
                return;
            }
            KernelState before = state;
            Decision decision = line.record().apply(line.envelope(), before);
            step.accept(line.envelope(), before, decision, line.fingerprints());
            state = decision.newState();
            seq = line.seq();
        }
    }
}
