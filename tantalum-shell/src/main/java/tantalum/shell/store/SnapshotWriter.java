package tantalum.shell.store;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.loop.Evictor;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/**
 * 快照的写入线程。cleanroom 只把不可变的状态放进队列。快照可丢弃：队列满或写失败只记日志，
 * 旧快照还在，最坏回到全量重放，运行不受影响。
 */
public final class SnapshotWriter implements Evictor.Sink, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotWriter.class);
    private static final int CAPACITY = 1024;
    private static final long CLOSE_WAIT_MILLIS = 10_000;
    private static final Object STOP = new Object();

    private record Request(SessionState session, List<RunState> runs, Transcript.Part transcript, long seq) {}

    private record Barrier(CountDownLatch done) {}

    private final SnapshotStore store;
    private final SessionPaths paths;
    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final Thread thread;

    public SnapshotWriter(SnapshotStore store, SessionPaths paths) {
        this.store = store;
        this.paths = paths;
        this.thread = Thread.ofPlatform().daemon().name("snapshot-writer").start(this::run);
    }

    @Override
    public void offer(SessionState session, List<RunState> runs, Transcript.Part transcript, long seq) {
        if (!queue.offer(new Request(session, runs, transcript, seq))) {
            LOG.warn("snapshot queue full, skipping session {}; it will replay from its session file", session.id().value());
        }
    }

    /** 等已入队的快照写完。 */
    public void flush() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        queue.put(new Barrier(done));
        while (!done.await(100, TimeUnit.MILLISECONDS) && thread.isAlive()) {
            // 线程意外退出时不再等。
        }
    }

    private void run() {
        try {
            while (true) {
                switch (queue.take()) {
                    case Request request -> write(request);
                    case Barrier barrier -> barrier.done().countDown();
                    default -> {
                        return;
                    }
                }
            }
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        }
    }

    private void write(Request request) {
        try {
            long offset = SessionFileReader.endOffsetOf(paths.sessionFile(request.session().principal(), request.session().id()), request.seq());
            if (offset < 0) {
                LOG.warn("no line with seq {} for session {}, skipping snapshot", request.seq(), request.session().id().value());
                return;
            }
            store.write(request.session().principal(), new SessionSnapshot(request.session(), request.runs(), request.transcript(), request.seq(), offset));
        } catch (IOException | RuntimeException failure) {
            LOG.warn("snapshot write failed for session {}, keeping the old one: {}", request.session().id().value(), failure.toString());
        }
    }

    /** 写完已入队的再退出。 */
    @Override
    public void close() throws InterruptedException {
        queue.put(STOP);
        thread.join(CLOSE_WAIT_MILLIS);
    }
}
