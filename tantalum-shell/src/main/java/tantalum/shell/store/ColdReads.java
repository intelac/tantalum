package tantalum.shell.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 不在内存的会话的只读查询：重放到私有状态，不进常驻。只读本人目录下的文件，所以别人的会话读不到。
 * 最近用过的留在缓存里，文件大小变了就重读，不会读到旧结果。
 */
public final class ColdReads {

    public static final int CAPACITY = 16;

    /** 重放一个会话的全部事件，没有事件时为空。 */
    @FunctionalInterface
    public interface Reader {
        Optional<SessionSnapshot> read(Principal principal, SessionId session) throws Exception;
    }

    private record Key(Principal principal, SessionId session) {}

    private record Cached(long size, Optional<SessionSnapshot> snapshot) {}

    private final SessionPaths paths;
    private final Reader reader;
    private final Map<Key, Cached> cache = new LinkedHashMap<>(CAPACITY, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
            return size() > CAPACITY;
        }
    };

    public ColdReads(SessionPaths paths, Reader reader) {
        this.paths = paths;
        this.reader = reader;
    }

    public ColdReads(SessionPaths paths, SessionReplay replay) {
        this(paths, (principal, session) -> replay.load(principal, session, Long.MAX_VALUE).map(SessionReplay.Slice::snapshot));
    }

    /** 文件不存在时为空，不重放。 */
    public Optional<SessionSnapshot> read(Principal principal, SessionId session) throws Exception {
        Path file = paths.sessionFile(principal, session);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        // 先取大小再读：读的过程中文件变长时，缓存记的是旧大小，下次会重读。
        long size = Files.size(file);
        Key key = new Key(principal, session);
        synchronized (cache) {
            Cached cached = cache.get(key);
            if (cached != null && cached.size() == size) {
                return cached.snapshot();
            }
        }
        Optional<SessionSnapshot> fresh = reader.read(principal, session);
        synchronized (cache) {
            cache.put(key, new Cached(size, fresh));
        }
        return fresh;
    }
}
