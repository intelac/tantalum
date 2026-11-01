package tantalum.shell.store;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.shell.journal.EventCodec;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 打开的 {@link SessionFile}，超过上限时关闭最久未用的。正在写的不会被关闭。
 * 文件 I/O 不持有全局锁，所以一个慢的 fsync 不会拖住其他会话。
 */
public final class SessionFiles implements AutoCloseable {

    private record Key(Principal principal, SessionId session) {}

    private static final class Handle {
        private SessionFile file;
        private int users;
    }

    private final SessionPaths paths;
    private final EventCodec codec;
    private final int maxOpen;
    private final Object lock = new Object();
    private final LinkedHashMap<Key, Handle> handles = new LinkedHashMap<>(16, 0.75f, true);

    public SessionFiles(SessionPaths paths, EventCodec codec, int maxOpen) {
        this.paths = paths;
        this.codec = codec;
        this.maxOpen = maxOpen;
    }

    /** 同一会话的事件追加到它的文件。返回时已落盘。 */
    public void append(Principal principal, SessionId session, List<EventEnvelope> events) throws IOException {
        Key key = new Key(principal, session);
        Handle handle;
        synchronized (lock) {
            handle = handles.computeIfAbsent(key, ignored -> new Handle());
            handle.users++;
        }
        try {
            synchronized (handle) {
                if (handle.file == null) {
                    handle.file = SessionFile.open(paths, principal, session, codec);
                }
                handle.file.append(events);
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
