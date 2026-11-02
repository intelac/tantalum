package tantalum.shell.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.event.EventEnvelope;

/** 读数据目录里的会话文件。同一用户的事件按序号合并；不同用户的序号不能比较，所以按用户依次排列。 */
public final class SessionStore {

    private final SessionPaths paths;
    private final SessionFileReader reader;

    public SessionStore(Path dataDir, EventCodec codec) {
        this.paths = new SessionPaths(dataDir);
        this.reader = new SessionFileReader(codec);
    }

    public List<EventEnvelope> readAll() {
        List<EventEnvelope> events = new ArrayList<>();
        try {
            for (Path dir : paths.users()) {
                events.addAll(readDir(dir));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return events;
    }

    private List<EventEnvelope> readDir(Path dir) throws IOException {
        List<EventEnvelope> events = new ArrayList<>();
        for (Path file : paths.sessionFiles(dir)) {
            reader.forEach(file, events::add);
        }
        events.sort(Comparator.comparingLong(EventEnvelope::seq));
        return events;
    }
}
