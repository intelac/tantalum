package tantalum.shell.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.local.fs.PrivateFiles;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.TokenUsage;

/**
 * 可重建的索引库：会话元数据、去重和用量。事实来源是会话文件，这里丢了可以从文件重建。
 * 同一批的三张表在一个事务里，所以文件位置不会走在内容前面。
 */
public final class SessionIndex implements AutoCloseable {

    public static final String FILE = "index.db";
    static final int VERSION = 2;
    private static final Logger LOG = LoggerFactory.getLogger(SessionIndex.class);

    /** 一个会话的元数据。只被认领、还没写过事件时 {@code lastActivity} 为 null；没有未结束的运行时 {@code activeRun} 为 null。 */
    public record Session(SessionId session, Principal principal, String title, Instant lastActivity, String runStatus,
                          RunId activeRun, long fileOffset) {}

    private final Connection connection;

    private SessionIndex(Connection connection) {
        this.connection = connection;
    }

    /** 文件损坏或版本不符时删掉重建，记警告；内容由 {@link IndexCatchUp} 从会话文件补回。 */
    public static SessionIndex open(Path dataDir) throws IOException, SQLException {
        Path file = dataDir.resolve(FILE);
        try {
            return openFile(file);
        } catch (SQLException | IllegalStateException unusable) {
            LOG.warn("index {} is unusable and will be rebuilt from session files: {}", file, unusable.getMessage());
            Files.deleteIfExists(file);
            Files.deleteIfExists(dataDir.resolve(FILE + "-wal"));
            Files.deleteIfExists(dataDir.resolve(FILE + "-shm"));
            return openFile(file);
        }
    }

    private static SessionIndex openFile(Path file) throws IOException, SQLException {
        if (!Files.exists(file)) {
            PrivateFiles.createFile(file);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = connection.createStatement()) {
            prepare(statement);
            return new SessionIndex(connection);
        } catch (SQLException | IllegalStateException failure) {
            connection.close();
            throw failure;
        }
    }

    private static void prepare(Statement statement) throws SQLException {
        int version;
        try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            version = result.getInt(1);
        }
        boolean empty;
        try (ResultSet result = statement.executeQuery("SELECT count(*) FROM sqlite_master")) {
            empty = result.getInt(1) == 0;
        }
        if (!empty && version != VERSION) {
            throw new IllegalStateException("index version " + version + " is not " + VERSION);
        }
        try (ResultSet result = statement.executeQuery("PRAGMA quick_check(1)")) {
            String verdict = result.getString(1);
            if (!"ok".equals(verdict)) {
                throw new IllegalStateException("quick_check: " + verdict);
            }
        }
        // 进程崩溃不丢已提交的事务；断电可能丢最后几个，启动时会从文件补齐。
        statement.execute("PRAGMA journal_mode=WAL");
        statement.execute("PRAGMA synchronous=NORMAL");
        statement.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                  session       TEXT PRIMARY KEY,
                  principal     TEXT NOT NULL,
                  title         TEXT NOT NULL,
                  last_activity TEXT,
                  run_status    TEXT NOT NULL,
                  active_run    TEXT,
                  file_offset   INTEGER NOT NULL
                )""");
        statement.execute("""
                CREATE TABLE IF NOT EXISTS submissions (
                  principal  TEXT NOT NULL,
                  request_id TEXT NOT NULL,
                  submission TEXT NOT NULL,
                  PRIMARY KEY (principal, request_id)
                ) WITHOUT ROWID""");
        statement.execute("""
                CREATE TABLE IF NOT EXISTS usage (
                  principal   TEXT NOT NULL,
                  seq         INTEGER NOT NULL,
                  day         TEXT NOT NULL,
                  input       INTEGER NOT NULL,
                  output      INTEGER NOT NULL,
                  cache_read  INTEGER NOT NULL,
                  cache_write INTEGER NOT NULL,
                  PRIMARY KEY (principal, seq)
                ) WITHOUT ROWID""");
        statement.execute("PRAGMA user_version=" + VERSION);
    }

    /** 认领会话 ID。已被别的用户占有时返回 false。这里是“同一新会话 ID 只属于一个用户”的唯一裁决点。 */
    public synchronized boolean claim(SessionId session, Principal principal) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO sessions (session, principal, title, run_status, file_offset)
                VALUES (?, ?, '', 'idle', 0)""")) {
            insert.setString(1, session.value());
            insert.setString(2, principal.value());
            insert.executeUpdate();
        }
        return session(session).orElseThrow().principal().equals(principal);
    }

    /** 重复写同一批不改变结果，所以重放可以重叠。归属不同的行不写入。 */
    synchronized void write(IndexBatch batch) throws SQLException {
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            writeSessions(batch);
            writeUsage(batch);
            writeSubmissions(batch);
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    private void writeSessions(IndexBatch batch) throws SQLException {
        try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO sessions (session, principal, title, last_activity, run_status, active_run, file_offset)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (session) DO UPDATE SET
                  title = CASE WHEN title = '' THEN excluded.title ELSE title END,
                  last_activity = excluded.last_activity,
                  run_status = excluded.run_status,
                  active_run = excluded.active_run,
                  file_offset = max(file_offset, excluded.file_offset)
                WHERE principal = excluded.principal""")) {
            for (IndexBatch.Row row : batch.sessions.values()) {
                upsert.setString(1, row.session.value());
                upsert.setString(2, row.principal.value());
                upsert.setString(3, row.title);
                upsert.setString(4, row.lastActivity.toString());
                upsert.setString(5, row.runStatus);
                upsert.setString(6, row.activeRun == null ? null : row.activeRun.value());
                upsert.setLong(7, row.fileOffset);
                upsert.addBatch();
            }
            upsert.executeBatch();
        }
    }

    private void writeUsage(IndexBatch batch) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO usage (principal, seq, day, input, output, cache_read, cache_write)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            for (IndexBatch.UsageRow row : batch.usage) {
                TokenUsage amount = row.usage().amount();
                insert.setString(1, row.principal().value());
                insert.setLong(2, row.usage().seq());
                insert.setString(3, row.usage().day().toString());
                insert.setLong(4, amount.input());
                insert.setLong(5, amount.output());
                insert.setLong(6, amount.cacheRead());
                insert.setLong(7, amount.cacheWrite());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void writeSubmissions(IndexBatch batch) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO submissions (principal, request_id, submission) VALUES (?, ?, ?)")) {
            for (IndexBatch.SubmissionRow row : batch.submissions) {
                insert.setString(1, row.principal().value());
                insert.setString(2, row.submission().request().value());
                insert.setString(3, row.submission().id().value());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /** 重放完一个文件后写实际大小。文件可能比记录的短（残缺尾部被截掉），所以这里不取最大值。 */
    synchronized void setOffset(SessionId session, Principal principal, long offset) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE sessions SET file_offset = ? WHERE session = ? AND principal = ?")) {
            update.setLong(1, offset);
            update.setString(2, session.value());
            update.setString(3, principal.value());
            update.executeUpdate();
        }
    }

    public synchronized Optional<Session> session(SessionId session) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(SELECT_SESSION + " WHERE session = ?")) {
            select.setString(1, session.value());
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? Optional.of(session(result)) : Optional.empty();
            }
        }
    }

    public synchronized List<Session> sessions() throws SQLException {
        List<Session> sessions = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(SELECT_SESSION + " ORDER BY session");
                ResultSet result = select.executeQuery()) {
            while (result.next()) {
                sessions.add(session(result));
            }
        }
        return sessions;
    }

    /** 有未结束运行的会话：启动时要给这些运行收尾。 */
    public synchronized List<Session> unfinished() throws SQLException {
        List<Session> sessions = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(SELECT_SESSION + " WHERE active_run IS NOT NULL ORDER BY session");
                ResultSet result = select.executeQuery()) {
            while (result.next()) {
                sessions.add(session(result));
            }
        }
        return sessions;
    }

    /** 一个用户写过事件的会话；只认领还没写过的不算。 */
    public synchronized List<Session> sessions(Principal principal) throws SQLException {
        List<Session> sessions = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(SELECT_SESSION + " WHERE principal = ? AND last_activity IS NOT NULL")) {
            select.setString(1, principal.value());
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    sessions.add(session(result));
                }
            }
        }
        return sessions;
    }

    private static final String SELECT_SESSION =
            "SELECT session, principal, title, last_activity, run_status, active_run, file_offset FROM sessions";

    private static Session session(ResultSet result) throws SQLException {
        String at = result.getString(4);
        String run = result.getString(6);
        return new Session(new SessionId(result.getString(1)), new Principal(result.getString(2)), result.getString(3),
                at == null ? null : Instant.parse(at), result.getString(5), run == null ? null : new RunId(run), result.getLong(7));
    }

    public synchronized Optional<SubmissionId> submission(Principal principal, RequestId request) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT submission FROM submissions WHERE principal = ? AND request_id = ?")) {
            select.setString(1, principal.value());
            select.setString(2, request.value());
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? Optional.of(new SubmissionId(result.getString(1))) : Optional.empty();
            }
        }
    }

    /** 该用户的全部用量，按序号排列。 */
    public List<IndexChange.Usage> usage(Principal principal) throws SQLException {
        return usageFrom(principal, Long.MIN_VALUE);
    }

    /** 序号不小于 {@code seq} 的用量，按序号排列。 */
    public synchronized List<IndexChange.Usage> usageFrom(Principal principal, long seq) throws SQLException {
        List<IndexChange.Usage> rows = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT seq, day, input, output, cache_read, cache_write FROM usage WHERE principal = ? AND seq >= ? ORDER BY seq")) {
            select.setString(1, principal.value());
            select.setLong(2, seq);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    rows.add(new IndexChange.Usage(result.getLong(1), LocalDate.parse(result.getString(2)),
                            new TokenUsage(result.getLong(3), result.getLong(4), result.getLong(5), result.getLong(6))));
                }
            }
        }
        return rows;
    }

    /** 序号小于 {@code seq} 的用量，按天累计。 */
    public synchronized Map<LocalDate, TokenUsage> usageBefore(Principal principal, long seq) throws SQLException {
        Map<LocalDate, TokenUsage> totals = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT day, sum(input), sum(output), sum(cache_read), sum(cache_write) FROM usage
                WHERE principal = ? AND seq < ? GROUP BY day""")) {
            select.setString(1, principal.value());
            select.setLong(2, seq);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    totals.put(LocalDate.parse(result.getString(1)),
                            new TokenUsage(result.getLong(2), result.getLong(3), result.getLong(4), result.getLong(5)));
                }
            }
        }
        return totals;
    }

    /** 全部请求 ID 的数量，供测试和诊断。 */
    public synchronized long submissionCount() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count(*) FROM submissions")) {
            return result.getLong(1);
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
