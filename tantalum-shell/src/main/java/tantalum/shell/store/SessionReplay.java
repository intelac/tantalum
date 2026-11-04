package tantalum.shell.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import tantalum.kernel.Kernel;
import tantalum.shell.conversation.Transcript;
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
 * 读一个会话文件，从头重放出这个会话的内存状态，不碰其他会话。
 * 内核是纯函数，可以在任何线程调。每个事件前把用量设为“截至这个序号的累计”，钩子因此看到和当时相同的值。
 */
public final class SessionReplay {

    /** 每个事件经过内核之后调用：处理前的状态和内核的决定。 */
    @FunctionalInterface
    public interface Step {
        void accept(EventEnvelope envelope, KernelState before, Decision decision);
    }

    /** 某个用户截至某个序号的累计用量从哪来：服务器读索引库，检查工具从结果行算。 */
    @FunctionalInterface
    public interface Usage {
        UsageLedger.Cursor cursor(Principal principal, long seq) throws SQLException, InterruptedException;
    }

    private final SessionPaths paths;
    private final SessionFileReader reader;
    private final Usage usage;
    private final Kernel kernel;

    public SessionReplay(SessionPaths paths, SessionFileReader reader, UsageLedger usage, Kernel kernel) {
        this(paths, reader, (Usage) usage::cursor, kernel);
    }

    public SessionReplay(SessionPaths paths, SessionFileReader reader, Usage usage, Kernel kernel) {
        this.paths = paths;
        this.reader = reader;
        this.usage = usage;
        this.kernel = kernel;
    }

    /** 重放序号小于 {@code beforeSeq} 的事件。没有这样的事件时为空。文件中间损坏时抛 {@link SessionCorruptException}。 */
    public Optional<SessionSlice> load(Principal principal, SessionId session, long beforeSeq) throws IOException, SQLException, InterruptedException {
        Path file = paths.sessionFile(principal, session);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Progress progress = new Progress();
        Transcript transcript = new Transcript();
        Trace trace = new Trace();
        Projections projections = new Projections(transcript, trace);
        replay(principal, file, progress, beforeSeq, projections::apply);
        if (progress.seq < 0 || progress.state.sessions().get(session) == null) {
            return Optional.empty();
        }
        return Optional.of(new SessionSlice(progress.state.sessions().get(session), List.copyOf(progress.state.runs().values()),
                transcript.export(session), trace.export(session), progress.seq));
    }

    /**
     * 从头重放一个会话文件的全部事件，每个事件前注入截至该序号的用量，所以钩子的结果和当时相同。
     * 回答要逐个事件内容的问题（{@code /turns}、{@code /trace}、SSE 补读）。文件不存在时什么也不做。
     * 收文件而不是会话 ID：SSE 补读按目录列出文件，长 ID 的文件名是哈希，反推不出 ID。
     */
    public void each(Principal principal, Path file, Step step) throws IOException, SQLException, InterruptedException {
        replay(principal, file, new Progress(), Long.MAX_VALUE, step);
    }

    /** 同 {@link #each}，只重放序号小于 {@code beforeSeq} 的事件（请求详情只需要到发出那条命令为止）。 */
    void until(Principal principal, Path file, long beforeSeq, Step step) throws IOException, SQLException, InterruptedException {
        replay(principal, file, new Progress(), beforeSeq, step);
    }

    /** 从头重放到 {@code beforeSeq} 之前。用量在第一个要重放的事件前才查。 */
    private void replay(Principal principal, Path file, Progress progress, long beforeSeq, Step step) throws IOException, SQLException, InterruptedException {
        try {
            reader.forEach(file, envelope -> {
                if (envelope.seq() >= beforeSeq) {
                    return;
                }
                if (progress.cursor == null) {
                    progress.cursor = cursor(principal, 0);
                }
                KernelState before = progress.state.with(progress.cursor.before(envelope.seq()));
                Decision decision = kernel.step(before, envelope);
                progress.state = decision.newState();
                step.accept(envelope, before, decision);
                progress.seq = envelope.seq();
            });
        } catch (UsageUnavailable failure) {
            switch (failure.getCause()) {
                case SQLException sql -> throw sql;
                case InterruptedException interrupted -> throw interrupted;
                default -> throw failure;
            }
        }
    }

    /** 只在真有事件要重放时才等写入线程和查库：全新会话走这条路径时不用付这个代价。 */
    private UsageLedger.Cursor cursor(Principal principal, long seq) {
        try {
            return usage.cursor(principal, seq);
        } catch (SQLException | InterruptedException failure) {
            throw new UsageUnavailable(failure);
        }
    }

    private static final class UsageUnavailable extends RuntimeException {
        UsageUnavailable(Exception cause) {
            super(cause);
        }
    }

    private static final class Progress {
        UsageLedger.Cursor cursor;
        KernelState state = KernelState.empty();
        long seq = -1;
    }
}
