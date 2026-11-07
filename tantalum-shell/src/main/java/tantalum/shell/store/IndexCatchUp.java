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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.kernel.Kernel;
import tantalum.shell.journal.SessionCorruptException;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.event.EventEnvelope;
import tantalum.value.state.KernelState;
import tantalum.value.state.PrincipalState;

/**
 * 启动时让索引追上会话文件。只比较文件大小，不读内容；大小不符的文件按记下的结果折叠一遍，不调内核：
 * 用量就是每一行记的差值，所以也不用按用户合并。写入都可以重复，所以和已有内容重叠没有关系。
 */
public final class IndexCatchUp {

    private static final Logger LOG = LoggerFactory.getLogger(IndexCatchUp.class);

    private IndexCatchUp() {}

    /** 单个文件读不了时跳过，索引保持落后，下次启动再试。 */
    public static void run(SessionIndex index, SessionPaths paths, SessionFileReader reader) throws IOException, SQLException {
        Map<Path, SessionIndex.Session> known = new HashMap<>();
        for (SessionIndex.Session session : index.sessions()) {
            known.put(paths.sessionFile(session.principal(), session.session()), session);
        }
        RecordReplay records = new RecordReplay(paths, reader);
        int caughtUp = 0;
        for (Path dir : paths.users()) {
            for (Path file : paths.sessionFiles(dir)) {
                long size = Files.size(file);
                SessionIndex.Session session = known.get(file);
                if (session != null && session.fileOffset() == size) {
                    continue;
                }
                IndexBatch batch = new IndexBatch();
                EventEnvelope[] first = new EventEnvelope[1];
                try {
                    records.each(null, file, (envelope, before, decision) -> {
                        if (first[0] == null) {
                            first[0] = envelope;
                        }
                        batch.add(IndexChange.of(envelope, before, decision.newState()));
                    });
                } catch (SessionCorruptException corrupt) {
                    LOG.warn("index skips a corrupt session file: {}", corrupt.getMessage());
                    continue;
                }
                index.write(batch);
                if (first[0] != null) {
                    index.setOffset(first[0].session(), first[0].principal(), size);
                } else if (session != null) {
                    index.setOffset(session.session(), session.principal(), size);
                }
                caughtUp++;
            }
        }
        if (caughtUp > 0) {
            LOG.info("index caught up with {} session files", caughtUp);
        }
    }
}
