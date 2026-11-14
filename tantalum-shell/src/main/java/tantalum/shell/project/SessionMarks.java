package tantalum.shell.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import tantalum.local.fs.PrivateFiles;
import tantalum.shell.auth.UserStore;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 每个用户归档了哪些会话、置顶了哪些会话。存在 {@code users.db} 的 {@code archived_sessions}、{@code pinned_sessions} 表里，
 * 不进 journal（同 DSH：归档和置顶是账外的附加信息）。会话本身和它的账都不动；能不能归档（运行中、等回答时不能）、
 * 能不能置顶（已归档的不能）由调用方判断。归档时同一个事务里取消置顶，取消归档不恢复置顶
 * （DSH {@code 2026-09-18-session-pin-and-sidebar-archive}）。
 */
public final class SessionMarks implements AutoCloseable {

    private final Connection connection;
    private final Clock clock;

    private SessionMarks(Connection connection, Clock clock) {
        this.connection = connection;
        this.clock = clock;
    }

    /** 不落盘：测试和嵌入使用。 */
    public static SessionMarks inMemory(Clock clock) throws SQLException {
        return create(DriverManager.getConnection("jdbc:sqlite::memory:"), clock);
    }

    /** 和用户表同一个文件。 */
    public static SessionMarks open(Path dataDir, Clock clock) throws IOException, SQLException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve(UserStore.FILE);
        if (!Files.exists(file)) {
            PrivateFiles.createFile(file);
        }
        return create(DriverManager.getConnection("jdbc:sqlite:" + file), clock);
    }

    private static SessionMarks create(Connection connection, Clock clock) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS archived_sessions (
                      principal   TEXT NOT NULL,
                      session     TEXT NOT NULL,
                      archived_at INTEGER NOT NULL,
                      PRIMARY KEY (principal, session)
                    ) WITHOUT ROWID""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS pinned_sessions (
                      principal TEXT NOT NULL,
                      session   TEXT NOT NULL,
                      pinned_at INTEGER NOT NULL,
                      PRIMARY KEY (principal, session)
                    ) WITHOUT ROWID""");
        }
        return new SessionMarks(connection, clock);
    }

    /** 归档或取消归档。归档同时取消置顶。重复设同一个值没有影响。 */
    public synchronized void setArchived(Principal user, SessionId session, boolean archived) throws SQLException {
        if (!archived) {
            change("DELETE FROM archived_sessions WHERE principal = ? AND session = ?", user, session, false);
            return;
        }
        connection.setAutoCommit(false);
        try {
            change("INSERT OR IGNORE INTO archived_sessions (principal, session, archived_at) VALUES (?, ?, ?)", user, session, true);
            change("DELETE FROM pinned_sessions WHERE principal = ? AND session = ?", user, session, false);
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** 置顶或取消置顶。重复设同一个值没有影响。 */
    public synchronized void setPinned(Principal user, SessionId session, boolean pinned) throws SQLException {
        change(pinned
                ? "INSERT OR IGNORE INTO pinned_sessions (principal, session, pinned_at) VALUES (?, ?, ?)"
                : "DELETE FROM pinned_sessions WHERE principal = ? AND session = ?", user, session, pinned);
    }

    private void change(String sql, Principal user, SessionId session, boolean stamped) throws SQLException {
        try (PreparedStatement change = connection.prepareStatement(sql)) {
            change.setString(1, user.value());
            change.setString(2, session.value());
            if (stamped) {
                change.setLong(3, clock.millis());
            }
            change.executeUpdate();
        }
    }

    public synchronized boolean archived(Principal user, SessionId session) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT 1 FROM archived_sessions WHERE principal = ? AND session = ?")) {
            select.setString(1, user.value());
            select.setString(2, session.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** 这个人归档的全部会话。 */
    public synchronized Set<SessionId> allArchived(Principal user) throws SQLException {
        return all("SELECT session FROM archived_sessions WHERE principal = ?", user);
    }

    /** 这个人置顶的全部会话。 */
    public synchronized Set<SessionId> allPinned(Principal user) throws SQLException {
        return all("SELECT session FROM pinned_sessions WHERE principal = ?", user);
    }

    private Set<SessionId> all(String sql, Principal user) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, user.value());
            Set<SessionId> found = new HashSet<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    found.add(new SessionId(rows.getString(1)));
                }
            }
            return Set.copyOf(found);
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
