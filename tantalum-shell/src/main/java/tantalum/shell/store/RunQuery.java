package tantalum.shell.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.store.SessionQueries.SessionUnreadable;
import tantalum.shell.trace.RunTree;
import tantalum.shell.trace.Trace;
import tantalum.shell.trace.TraceRecord;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/**
 * 运行的查询（run-tree 需求 1、4）：一次运行的调用树每次重放会话文件算，和轨迹接口出自同一个投影；运行列表查索引库。
 * 只读本人目录下的文件、本人的行，别人的和不存在一样。
 */
public final class RunQuery {

    /** 索引库查不了。接口返回 503。 */
    public static final class RunsUnavailable extends RuntimeException {
        RunsUnavailable(Throwable cause) {
            super("run index unavailable", cause);
        }
    }

    private final SessionPaths paths;
    private final RecordReplay replay;
    private final SessionIndex index;

    public RunQuery(SessionPaths paths, RecordReplay replay, SessionIndex index) {
        this.paths = paths;
        this.replay = replay;
        this.index = index;
    }

    /** 会话不存在、不是本人的，或会话里没有这个运行时为空。读不了文件时抛 {@link SessionUnreadable}。 */
    public Optional<RunTree> tree(Principal principal, SessionId session, RunId run) {
        Path file = paths.sessionFile(principal, session);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Trace trace = new Trace();
        Map<String, TraceRecord> records = new LinkedHashMap<>();
        try {
            replay.each(principal, file, (envelope, before, decision) -> {
                for (TraceRecord record : trace.apply(envelope, before, decision)) {
                    if (record.head().run().equals(run)) {
                        records.put(record.id(), record);
                    }
                }
            });
        } catch (Exception failure) {
            throw new SessionUnreadable(session, failure);
        }
        return records.isEmpty() ? Optional.empty() : Optional.of(RunTree.of(run, records.values()));
    }

    /** 这个人的运行，一页。游标不合法时抛 {@link IllegalArgumentException}。 */
    public RunTable.Page list(Principal principal, RunTable.Filter filter) {
        try {
            return index.runs(principal, filter);
        } catch (SQLException failure) {
            throw new RunsUnavailable(failure);
        }
    }
}
