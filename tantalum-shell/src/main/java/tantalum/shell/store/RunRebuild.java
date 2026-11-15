package tantalum.shell.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.trace.RunRow;
import tantalum.shell.trace.Trace;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/**
 * 从会话文件重算一个运行的最终一行（重启前开始、重启后结束的运行，run-tree 需求 3.5）。在自己的一个线程里排队做，
 * 不占写入通道和净室；结束那一步已经落盘（轨迹记录落盘后才放出来），所以文件里有完整的运行。
 */
public final class RunRebuild implements RunCollector.Rebuild, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RunRebuild.class);

    private final SessionPaths paths;
    private final RecordReplay replay;
    private final Consumer<RunRow> emit;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("run-rebuild").factory());

    public RunRebuild(SessionPaths paths, RecordReplay replay, Consumer<RunRow> emit) {
        this.paths = paths;
        this.replay = replay;
        this.emit = emit;
    }

    @Override
    public void rebuild(Principal owner, SessionId session, RunId run) {
        worker.execute(() -> {
            try {
                RunRow row = replayRun(paths.sessionFile(owner, session), replay, owner, run);
                if (row != null) {
                    emit.accept(row);
                } else {
                    LOG.warn("run {} not found in its session file; its index row stays as it was", run.value());
                }
            } catch (IOException | RuntimeException failure) {
                LOG.error("cannot rebuild the index row of run {}", run.value(), failure);
            }
        });
    }

    /** 重放整个文件，只留 {@code run} 结束时的那一行；没有结束或没有这个运行时为 null。 */
    static RunRow replayRun(Path file, RecordReplay replay, Principal owner, RunId run) throws IOException {
        RunRow[] found = new RunRow[1];
        RunCollector collector = new RunCollector(row -> {
            if (row.run().equals(run) && row.end() != null) {
                found[0] = row;
            }
        }, (ignoredOwner, ignoredSession, ignoredRun) -> { });
        Trace trace = new Trace();
        replay.each(owner, file, (envelope, before, decision) ->
                collector.onRecords(envelope.seq(), owner, trace.apply(envelope, before, decision)));
        return found[0];
    }

    @Override
    public void close() throws InterruptedException {
        worker.shutdown();
        worker.awaitTermination(10, TimeUnit.SECONDS);
    }
}
