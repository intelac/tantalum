package tantalum.shell.journal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFile;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 打开的 {@link SessionFile}，超过上限时关闭最久未用的。正在写的不会被关闭。
 * 文件 I/O 不持有全局锁，所以一个慢的 fsync 不会拖住其他会话。
 * 每个会话另有一份 {@link RecordMemo}，编码结果行时省掉文件里已有的内容；它不随文件句柄关闭，随会话离开内存（{@link #forget}）。
 */
public final class SessionFiles implements AutoCloseable {

    /** 一批行落盘后调用：序号不超过 {@code seq} 的行已落盘，文件到 {@code offset}。 */
    @FunctionalInterface
    public interface DurableListener {
        void onDurable(Principal principal, SessionId session, long seq, long offset);

        /** 一份快照落盘了，快照行从 {@code offset} 开始（索引记下它，载入从这里读起）。 */
        default void onSnapshot(Principal principal, SessionId session, long offset) {
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(SessionFiles.class);

    /**
     * 一个会话离上一份快照多远：上一份快照（或文件头）结束的位置、它的大小，和要不要下一份（{@link #takeWanted}）。
     * 落盘后在写入线程上更新，净室在自己的线程上取旗，所以旗是原子的。
     */
    private static final class Distance {
        private volatile long since;
        private volatile long snapshotBytes;
        private final AtomicBoolean wanted = new AtomicBoolean();
    }

    private final Map<Key, Distance> distances = new ConcurrentHashMap<>();
    /** 自上一份快照写过这么多字节、且不少于两倍快照大小，就要下一份（{@code tantalum.journal.snapshot-bytes}）。 */
    private volatile long snapshotBytes = DEFAULT_SNAPSHOT_BYTES;
    public static final long DEFAULT_SNAPSHOT_BYTES = 8L * 1024 * 1024;

    private record Key(Principal principal, SessionId session) {}

    private static final class Handle {
        private SessionFile file;
        private int users;
    }

    private final SessionPaths paths;
    private final int maxOpen;
    private final DurableListener listener;
    private final Object lock = new Object();
    private final LinkedHashMap<Key, Handle> handles = new LinkedHashMap<>(16, 0.75f, true);
    private final LineCodec codec;
    private final Map<Key, RecordMemo> memos = new ConcurrentHashMap<>();

    public SessionFiles(SessionPaths paths, EventCodec events, int maxOpen, DurableListener listener) {
        this.listener = listener;
        // 测试和不配置时用默认值，见 snapshotBytes(long)
        this.paths = paths;
        this.codec = new LineCodec(events);
        this.maxOpen = maxOpen;
    }

    /** 写多少字节以后要一份快照（不小于两倍上一份快照的大小）。 */
    public SessionFiles snapshotBytes(long bytes) {
        this.snapshotBytes = Math.max(1, bytes);
        return this;
    }

    /** 这个会话要一份快照：取一次就放下旗，所以连着几步只做一份。 */
    public boolean takeWanted(Principal principal, SessionId session) {
        Distance distance = distances.get(new Key(principal, session));
        return distance != null && distance.wanted.getAndSet(false);
    }

    /** 同 {@link #append(Principal, SessionId, List, Snapshot)}，不带快照。 */
    public void append(Principal principal, SessionId session, List<Line> lines) throws IOException {
        append(principal, session, lines, null);
    }

    /**
     * 同一会话处理完的几步按顺序追加，fsync 一次，返回时已落盘，然后通知监听器。
     * 写失败时丢掉这个会话的 memo：它可能记下了没落盘的内容，之后的行不能引用它们。
     * {@code snapshot} 不为空时它是最后一步之后的样子：步落盘以后紧跟着单独写快照行；写快照失败不影响已经落盘的步，
     * 截回、记日志，下次再要（见 {@link #writeSnapshot}）。
     */
    public void append(Principal principal, SessionId session, List<Line> lines, Snapshot snapshot) throws IOException {
        Key key = new Key(principal, session);
        Handle handle;
        synchronized (lock) {
            handle = handles.computeIfAbsent(key, ignored -> new Handle());
            handle.users++;
        }
        try {
            synchronized (handle) {
                RecordMemo memo = memos.get(key);
                if (memo == null) {
                    Path file = paths.sessionFile(principal, session);
                    memo = Files.exists(file) && Files.size(file) > 0 ? RecordMemo.blind(principal, session)
                            : RecordMemo.writer(principal, session);
                    memos.put(key, memo);
                }
                if (handle.file == null) {
                    handle.file = SessionFile.open(paths, principal, session);
                }
                List<String> encoded = new ArrayList<>(lines.size());
                long seq = Long.MIN_VALUE;
                try {
                    for (Line line : lines) {
                        handle.file.check(line.envelope());
                        encoded.add(codec.encode(line, memo));
                        seq = Math.max(seq, line.seq());
                    }
                    long offset = handle.file.appendLines(encoded);
                    listener.onDurable(principal, session, seq, offset);
                    Distance distance = distances.computeIfAbsent(key, ignored -> new Distance());
                    if (snapshot != null) {
                        offset = writeSnapshot(key, handle, snapshot, offset, distance);
                    }
                    if (offset - distance.since > Math.max(snapshotBytes, 2 * distance.snapshotBytes)) {
                        distance.wanted.set(true);
                    }
                } catch (IOException | RuntimeException failure) {
                    // memo 可能已领先于文件，句柄可能带着残缺的尾部：都丢掉，下次重新打开
                    memos.remove(key);
                    try {
                        handle.file.close();
                    } catch (IOException | RuntimeException cannotClose) {
                        failure.addSuppressed(cannotClose);
                    }
                    handle.file = null;
                    throw failure;
                }
            }
        } finally {
            release(handle);
        }
    }

    private void release(Handle handle) throws IOException {
        List<Handle> evicted = new ArrayList<>();
        synchronized (lock) {
            handle.users--;
            Iterator<Map.Entry<Key, Handle>> oldest = handles.entrySet().iterator();
            while (handles.size() > maxOpen && oldest.hasNext()) {
                Handle candidate = oldest.next().getValue();
                if (candidate.users == 0) {
                    oldest.remove();
                    evicted.add(candidate);
                }
            }
        }
        closeAll(evicted);
    }

    /**
     * 紧跟在刚落盘的步后面写快照行。成功：换成从快照起头的 memo，记下距离，告诉监听器。失败：文件截回写之前的长度
     * （{@link SessionFile#appendLines} 失败时会截），句柄作废、下次重开；memo 不换，步已经落盘不受影响。返回文件现在的长度。
     */
    private long writeSnapshot(Key key, Handle handle, Snapshot snapshot, long before, Distance distance) throws IOException {
        try {
            long after = handle.file.appendLines(List.of(codec.encode(snapshot)));
            memos.put(key, RecordMemo.afterSnapshot(false, key.principal(), key.session(), snapshot));
            distance.since = after;
            distance.snapshotBytes = after - before;
            listener.onSnapshot(key.principal(), key.session(), before);
            return after;
        } catch (IOException | RuntimeException failure) {
            LOG.warn("could not write a snapshot of {}/{}; the steps are safe, will try again later: {}", key.principal().value(),
                    key.session().value(), failure.toString());
            // 写到一半失败时 appendLines 已经截回并作废了这个句柄；编码失败时文件没动。两种都关掉重开，下次打开会修尾部
            try {
                handle.file.close();
            } catch (IOException | RuntimeException cannotClose) {
                failure.addSuppressed(cannotClose);
            }
            handle.file = null;
            return before;
        }
    }

    /**
     * 冷载入读完文件后换上读出来的 memo：从此结果行能引用文件里已有的内容。{@code from} 是载入读起的位置（最后一份快照，没有为 0），
     * {@code snapshotBytes} 是那份快照的大小：之后什么时候要下一份从这里量。
     */
    public void adopt(Principal principal, SessionId session, RecordMemo memo, long from, long snapshotBytes) {
        Key key = new Key(principal, session);
        memos.put(key, memo);
        Distance distance = new Distance();
        distance.since = from + snapshotBytes;
        distance.snapshotBytes = snapshotBytes;
        distances.put(key, distance);
    }

    /** 会话离开内存：它的 memo 不再需要。下次写入时如果文件已存在，先用不引用任何内容的 memo。 */
    public void forget(SessionId session) {
        memos.keySet().removeIf(key -> key.session().equals(session));
        distances.keySet().removeIf(key -> key.session().equals(session));
    }

    int openCount() {
        synchronized (lock) {
            return handles.size();
        }
    }

    @Override
    public void close() throws IOException {
        List<Handle> all;
        synchronized (lock) {
            all = new ArrayList<>(handles.values());
            handles.clear();
        }
        closeAll(all);
    }

    private static void closeAll(List<Handle> closing) throws IOException {
        IOException failure = null;
        for (Handle handle : closing) {
            try {
                synchronized (handle) {
                    if (handle.file != null) {
                        handle.file.close();
                    }
                }
            } catch (IOException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
