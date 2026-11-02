package tantalum.shell.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import tantalum.kernel.Kernel;
import tantalum.shell.conversation.Transcript;
import tantalum.value.decision.Decision;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunState;

/**
 * 读一个会话文件，重放出这个会话的内存状态，不碰其他会话。有效的快照可以省掉前面的部分。
 * 内核是纯函数，可以在任何线程调。每个事件前把用量设为“截至这个序号的累计”（H4），钩子因此看到和当时相同的值。
 */
public final class SessionReplay {

    /** 重放的结果。{@code snapshot} 可以直接写成快照；{@code replayedEvents} 是这次真正重放的事件数。 */
    public record Slice(SessionSnapshot snapshot, int replayedEvents) {}

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
    public Optional<Slice> load(Principal principal, SessionId session, long beforeSeq) throws IOException, SQLException, InterruptedException {
        Path file = paths.sessionFile(principal, session);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Optional<SessionSnapshot> snapshot = snapshots.load(principal, session, beforeSeq);
        Progress progress = new Progress();
        snapshot.ifPresent(found -> {
            progress.state = progress.state.with(found.session());
            found.runs().forEach(run -> progress.state = progress.state.with(run));
            progress.transcript.restore(session, found.transcript());
            progress.seq = found.seq();
            progress.offset = found.offset();
        });
        long usageFrom = snapshot.map(found -> found.seq() + 1).orElse(0L);
        try {
            reader.scan(file, snapshot.map(SessionSnapshot::offset).orElse(0L), (envelope, end) -> {
                if (envelope.seq() >= beforeSeq) {
                    return;
                }
                if (progress.cursor == null) {
                    progress.cursor = cursor(principal, usageFrom);
                }
                Decision decision = kernel.step(progress.state.with(progress.cursor.before(envelope.seq())), envelope);
                progress.state = decision.newState();
                progress.transcript.apply(envelope, decision);
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
        if (progress.seq < 0 || progress.state.sessions().get(session) == null) {
            return Optional.empty();
        }
        List<RunState> runs = List.copyOf(progress.state.runs().values());
        return Optional.of(new Slice(new SessionSnapshot(progress.state.sessions().get(session), runs,
                progress.transcript.export(session), progress.seq, progress.offset), progress.replayed));
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
        final Transcript transcript = new Transcript();
        long seq = -1;
        long offset;
        int replayed;
    }
}
