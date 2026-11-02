package tantalum.shell.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.kernel.Kernel;
import tantalum.shell.journal.SessionCorruptException;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.state.KernelState;
import tantalum.value.state.PrincipalState;

/**
 * 启动时让索引追上会话文件。只比较文件大小，不读内容；大小不符的文件整个重放，
 * 因为用量要比较内核状态才知道。写入都可以重复，所以重放和已有内容重叠没有关系。
 */
public final class IndexCatchUp {

    private static final Logger LOG = LoggerFactory.getLogger(IndexCatchUp.class);

    private record Lagging(Path file, long size, SessionIndex.Session known) {}

    private IndexCatchUp() {}

    /** 单个文件读不了时跳过，索引保持落后，下次启动再试。 */
    public static void run(SessionIndex index, SessionPaths paths, SessionFileReader reader, Kernel kernel) throws IOException, SQLException {
        Map<Path, SessionIndex.Session> known = new HashMap<>();
        for (SessionIndex.Session session : index.sessions()) {
            known.put(paths.sessionFile(session.principal(), session.session()), session);
        }
        int replayed = 0;
        for (Path dir : list(paths.sessionsDir())) {
            List<Lagging> lagging = new ArrayList<>();
            for (Path file : list(dir)) {
                long size = Files.size(file);
                SessionIndex.Session session = known.get(file);
                if (session == null || session.fileOffset() != size) {
                    lagging.add(new Lagging(file, size, session));
                }
            }
            if (!lagging.isEmpty()) {
                replayUser(index, reader, kernel, lagging);
                replayed += lagging.size();
            }
        }
        if (replayed > 0) {
            LOG.info("index caught up with {} session files", replayed);
        }
    }

    /** 同一用户的文件按序号合并后一起重放，用量才和当时相同。 */
    private static void replayUser(SessionIndex index, SessionFileReader reader, Kernel kernel, List<Lagging> lagging) throws IOException, SQLException {
        List<EventEnvelope> events = new ArrayList<>();
        Map<Lagging, EventEnvelope> first = new LinkedHashMap<>();
        for (Lagging file : lagging) {
            try {
                List<EventEnvelope> read = reader.readAll(file.file());
                events.addAll(read);
                if (!read.isEmpty()) {
                    first.put(file, read.getFirst());
                } else if (file.known() != null) {
                    index.setOffset(file.known().session(), file.known().principal(), file.size());
                }
            } catch (SessionCorruptException corrupt) {
                LOG.warn("index skips a corrupt session file: {}", corrupt.getMessage());
            }
        }
        if (events.isEmpty()) {
            return;
        }
        events.sort(Comparator.comparingLong(EventEnvelope::seq));
        index.write(replay(events, index.usage(events.getFirst().principal()), kernel));
        for (Map.Entry<Lagging, EventEnvelope> file : first.entrySet()) {
            index.setOffset(file.getValue().session(), file.getValue().principal(), file.getKey().size());
        }
    }

    /** 每个事件前把用量设为“截至这个序号的累计”：已有的记录加上这次重放算出来的，同一序号只算一次。 */
    private static IndexBatch replay(List<EventEnvelope> events, List<IndexChange.Usage> stored, Kernel kernel) {
        Principal principal = events.getFirst().principal();
        Set<Long> storedSeqs = new HashSet<>();
        stored.forEach(row -> storedSeqs.add(row.seq()));
        PrincipalState usage = PrincipalState.start(principal);
        KernelState state = KernelState.empty();
        IndexBatch batch = new IndexBatch();
        int next = 0;
        for (EventEnvelope envelope : events) {
            while (next < stored.size() && stored.get(next).seq() < envelope.seq()) {
                usage = usage.add(stored.get(next).day(), stored.get(next).amount());
                next++;
            }
            KernelState before = state.with(usage);
            state = kernel.step(before, envelope).newState();
            IndexChange change = IndexChange.of(envelope, before, state);
            batch.add(change);
            if (change.usage() != null && !storedSeqs.contains(envelope.seq())) {
                usage = usage.add(change.usage().day(), change.usage().amount());
            }
        }
        return batch;
    }

    private static List<Path> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.sorted().toList();
        }
    }
}
