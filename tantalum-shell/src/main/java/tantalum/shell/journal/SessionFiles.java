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
    }

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
        this.paths = paths;
        this.codec = new LineCodec(events);
        this.maxOpen = maxOpen;
    }

    /**
     * 同一会话处理完的几步按顺序追加，fsync 一次，返回时已落盘，然后通知监听器（one-line-journal J2）。
     * 写失败时丢掉这个会话的 memo：它可能记下了没落盘的内容，之后的行不能引用它们。
     */
    public void append(Principal principal, SessionId session, List<Line> lines) throws IOException {
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
                } catch (IOException | RuntimeException failure) {
                    memos.remove(key);
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

    /** 冷载入读完整个文件后换上读出来的 memo：从此结果行能引用文件里已有的内容。 */
    public void adopt(Principal principal, SessionId session, RecordMemo memo) {
        memos.put(new Key(principal, session), memo);
    }

    /** 会话离开内存：它的 memo 不再需要。下次写入时如果文件已存在，先用不引用任何内容的 memo。 */
    public void forget(SessionId session) {
        memos.keySet().removeIf(key -> key.session().equals(session));
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
