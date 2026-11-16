package tantalum.shell.delegate;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tantalum.local.fs.PrivateFiles;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 委派的记录（memory-final-plan 第 12 节）：哪个子会话是哪个会话的哪次工具调用开的、第几层。只插入；在数据目录的 {@code delegations.db}。
 * 子会话本身是普通会话，有自己的 journal；这里只记父子关系，给层数上限、子会话的权限和画面用。
 */
public final class Delegations implements AutoCloseable {

    /**
     * 不等结果的委派做完后，汇报以一次提交进父会话（{@code EngineDelegator.report}），journal 里看着和用户打的字一样。
     * 汇报都以这两句之一开头；{@code RunText.segments} 按它把这条当成委派工具的结果，不当成用户的话（自动记笔记只认用户亲口说的）。
     */
    public static final String REPORT = "Report from the delegated sub-conversation ";
    public static final String REPORT_PROBLEM = "The delegated sub-conversation ";

    /** 这段提交是不是委派的汇报。 */
    public static boolean isReport(String text) {
        return text != null && (text.startsWith(REPORT) || text.startsWith(REPORT_PROBLEM));
    }


    public static final String FILE = "delegations.db";

    /** 一次委派：子会话、父会话、父运行里那次工具调用的命令号、第几层（父会话自己不是子会话时是 1）、时刻。 */
    public record Delegation(Principal user, SessionId child, SessionId parent, String command, int depth, Instant at) {}

    private final Connection connection;

    private Delegations(Connection connection) {
        this.connection = connection;
    }

    public static Delegations open(Path dir) throws IOException, SQLException {
        Files.createDirectories(dir);
        Path file = dir.resolve(FILE);
        if (!Files.exists(file)) {
            PrivateFiles.createFile(file);
        }
        PrivateFiles.restrict(file);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS delegations (
                      user    TEXT NOT NULL,
                      child   TEXT PRIMARY KEY,
                      parent  TEXT NOT NULL,
                      command TEXT,
                      depth   INTEGER NOT NULL,
                      at      TEXT NOT NULL
                    )""");
            statement.execute("CREATE INDEX IF NOT EXISTS delegations_by_parent ON delegations (user, parent)");
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                failure.addSuppressed(ignored);
            }
            throw failure;
        }
        return new Delegations(connection);
    }

    public synchronized void record(Delegation delegation) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO delegations (user, child, parent, command, depth, at) VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, delegation.user().value());
            insert.setString(2, delegation.child().value());
            insert.setString(3, delegation.parent().value());
            insert.setString(4, delegation.command());
            insert.setInt(5, delegation.depth());
            insert.setString(6, delegation.at().toString());
            insert.executeUpdate();
        }
    }

    /** 这个会话是谁开的子会话；不是子会话为空。只认同一个人的。 */
    public synchronized Optional<Delegation> of(Principal user, SessionId child) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT parent, command, depth, at FROM delegations WHERE user = ? AND child = ?")) {
            select.setString(1, user.value());
            select.setString(2, child.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(new Delegation(user, child, new SessionId(rows.getString(1)), rows.getString(2),
                        rows.getInt(3), Instant.parse(rows.getString(4)))) : Optional.empty();
            }
        }
    }

    /** 这个人全部的委派，新的在前（侧栏把子会话缩进到父会话下面用）。 */
    public synchronized List<Delegation> all(Principal user) throws SQLException {
        List<Delegation> found = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT child, parent, command, depth, at FROM delegations WHERE user = ? ORDER BY at DESC")) {
            select.setString(1, user.value());
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    found.add(new Delegation(user, new SessionId(rows.getString(1)), new SessionId(rows.getString(2)), rows.getString(3),
                            rows.getInt(4), Instant.parse(rows.getString(5))));
                }
            }
        }
        return found;
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
