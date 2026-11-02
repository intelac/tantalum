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
import tantalum.shell.loop.SessionSlice;
import tantalum.value.decision.Decision;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.KernelState;

/**
 * 读一个会话文件，重放出这个会话的内存状态，不碰其他会话。有效的快照可以省掉前面的部分。
 * 内核是纯函数，可以在任何线程调。每个事件前把用量设为“截至这个序号的累计”，钩子因此看到和当时相同的值。
 */
public final class SessionReplay {

    /** 重放的结果。{@code snapshot} 可以直接写成快照；{@code replayedEvents} 是这次真正重放的事件数。 */
    public record Result(SessionSnapshot snapshot, int replayedEvents) {}

    /** 每个事件经过内核之后调用：处理前的状态和内核的决定。 */
    @FunctionalInterface
    public interface Step {
        void accept(EventEnvelope envelope, KernelState before, Decision decision);
    }

    private final SessionPaths paths;
    private final SessionFileReader reader;
    private final SnapshotStore snapshots;
    private final UsageLedger usage;
    private final Kernel kernel;

    public SessionReplay(SessionPaths paths, SessionFileReader reader, SnapshotStore snapshots, UsageLedger usage, Kernel kernel) {
        this.paths = paths;
        this.reader = reader;
        this.snapshots = snapshots;
        this.usage = usage;
        this.kernel = kernel;
    }

    /** 重放序号小于 {@code beforeSeq} 的事件。没有这样的事件时为空。文件中间损坏时抛 {@link SessionCorruptException}。 */
    public Optional<Result> load(Principal principal, SessionId session, long beforeSeq) throws IOException, SQLException, InterruptedException {
        Path file = paths.sessionFile(principal, session);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Optional<SessionSnapshot> snapshot = snapshots.load(principal, session, beforeSeq);
        Progress progress = new Progress();
        Transcript transcript = new Transcript();
        snapshot.ifPresent(found -> {
            SessionSlice slice = found.slice();
            progress.state = progress.state.with(slice.session());
            slice.runs().forEach(run -> progress.state = progress.state.with(run));
            transcript.restore(session, slice.transcript());
            progress.seq = slice.seq();
            progress.offset = found.offset();
        });
        replay(principal, file, progress, beforeSeq, (envelope, before, decision) -> transcript.apply(envelope, decision));
        if (progress.seq < 0 || progress.state.sessions().get(session) == null) {
            return Optional.empty();
        }
        SessionSlice slice = new SessionSlice(progress.state.sessions().get(session), List.copyOf(progress.state.runs().values()),
                transcript.export(session), progress.seq);
        return Optional.of(new Result(new SessionSnapshot(slice, progress.offset), progress.replayed));
    }

    /**
     * 从头重放一个会话文件的全部事件，每个事件前注入截至该序号的用量，所以钩子的结果和当时相同。
     * 回答要逐个事件内容的问题（{@code /turns}、{@code /trace}、SSE 补读），不用快照。文件不存在时什么也不做。
     * 收文件而不是会话 ID：SSE 补读按目录列出文件，长 ID 的文件名是哈希，反推不出 ID。
     */
    public void each(Principal principal, Path file, Step step) throws IOException, SQLException, InterruptedException {
        replay(principal, file, new Progress(), Long.MAX_VALUE, step);
    }

    /** 从 {@code progress} 的位置往后重放到 {@code beforeSeq} 之前。用量在第一个要重放的事件前才查，从 {@code progress} 之后的序号开始。 */
    private void replay(Principal principal, Path file, Progress progress, long beforeSeq, Step step) throws IOException, SQLException, InterruptedException {
        long usageFrom = progress.seq + 1;
        try {
            reader.scan(file, progress.offset, (envelope, end) -> {
                if (envelope.seq() >= beforeSeq) {
                    return;
                }
                if (progress.cursor == null) {
                    progress.cursor = cursor(principal, usageFrom);
                }
                KernelState before = progress.state.with(progress.cursor.before(envelope.seq()));
                Decision decision = kernel.step(before, envelope);
                progress.state = decision.newState();
                step.accept(envelope, before, decision);
                progress.seq = envelope.seq();
                progress.offset = end;
                progress.replayed++;
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
        long offset;
        int replayed;
    }
}
