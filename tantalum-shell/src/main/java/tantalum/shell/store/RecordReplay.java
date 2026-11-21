package tantalum.shell.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** 冷载入的结果，附带读完整个文件后的 memo，交给写入方接着写结果行。 */
    public record Loaded(SessionSlice slice, RecordMemo memo) {}


    private final SessionPaths paths;
    private final SessionFileReader reader;

    public RecordReplay(SessionPaths paths, SessionFileReader reader) {
        this.paths = paths;
        this.reader = reader;
    }

    /** 冷载入：折叠序号小于 {@code beforeSeq} 的步骤；没有这样的事件时为空。memo 总是读完整个文件。 */
    public Optional<Loaded> load(Principal principal, SessionId session, long beforeSeq) throws IOException {
        return fold(principal, session, beforeSeq);
    }

    /** 不在内存的会话的查询：折叠整个文件。 */
    public Optional<SessionSlice> read(Principal principal, SessionId session) throws IOException {
        return fold(principal, session, Long.MAX_VALUE).map(Loaded::slice);
    }

    private Optional<Loaded> fold(Principal principal, SessionId session, long beforeSeq) throws IOException {
        Path file = paths.sessionFile(principal, session);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Transcript transcript = new Transcript();
        Trace trace = new Trace();
        Projections projections = new Projections(transcript, trace);
        Fold fold = new Fold(beforeSeq, (envelope, before, decision, fingerprints) -> projections.apply(envelope, before, decision));
        RecordMemo memo = reader.forEachLine(file, fold::accept).toWriter();
        if (fold.seq < 0 || fold.state.sessions().get(session) == null) {
            return Optional.empty();
        }
        return Optional.of(new Loaded(new SessionSlice(fold.state.sessions().get(session), List.copyOf(fold.state.runs().values()),
                transcript.export(session), trace.export(session), fold.seq), memo));
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
