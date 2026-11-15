package tantalum.shell.auth;

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
import tantalum.local.fs.PrivateFiles;

/**
 * 管理员只读查看的记录（memory-final-plan 第 13 节，Yi 2026-11-15 定“每次查看都记下”）：谁、看了谁、哪个会话、什么时候。
 * 只插入，不改不删；被看的人自己能查（{@code GET /api/settings/views}）。放在数据目录的 {@code views.db}。
 */
public final class ViewLog implements AutoCloseable {

    public static final String FILE = "views.db";

    /** 一条记录；看的是会话列表这类不属于某个会话的接口时 {@code session} 为 null。 */
    public record View(String viewer, String session, Instant at) {}

    private final Connection connection;

    private ViewLog(Connection connection) {
        this.connection = connection;
    }

    public static ViewLog open(Path dir) throws IOException, SQLException {
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
                    CREATE TABLE IF NOT EXISTS views (
                      viewed  TEXT NOT NULL,
                      viewer  TEXT NOT NULL,
                      session TEXT,
                      at      TEXT NOT NULL
                    )""");
            statement.execute("CREATE INDEX IF NOT EXISTS views_by_viewed ON views (viewed, at)");
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                failure.addSuppressed(ignored);
            }
            throw failure;
        }
        return new ViewLog(connection);
    }

    public synchronized void record(String viewed, String viewer, String session, Instant at) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO views (viewed, viewer, session, at) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, viewed);
            insert.setString(2, viewer);
            insert.setString(3, session);
            insert.setString(4, at.toString());
            insert.executeUpdate();
        }
    }

    /** 这个人被看过的记录，新的在前，最多 {@code limit} 条。 */
    public synchronized List<View> of(String viewed, int limit) throws SQLException {
        List<View> views = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT viewer, session, at FROM views WHERE viewed = ? ORDER BY at DESC, rowid DESC LIMIT ?")) {
            select.setString(1, viewed);
            select.setInt(2, limit);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    views.add(new View(rows.getString(1), rows.getString(2), Instant.parse(rows.getString(3))));
                }
            }
        }
        return views;
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
