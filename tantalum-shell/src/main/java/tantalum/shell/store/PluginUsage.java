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
import java.util.ArrayList;
import java.util.List;
import tantalum.local.fs.PrivateFiles;

/**
 * 插件调模型的用量账（memory-final-plan 第 15 节）：每次一行——谁、哪个插件、做什么、哪条路线和模型、输入输出 token、多久、结果。
 * 不算每日额度（Yi 11-15：记笔记的 token 不算）。只插入，不改不删；在数据目录的 {@code plugin-usage.db}。
 */
public final class PluginUsage implements AutoCloseable {

    public static final String FILE = "plugin-usage.db";

    /** 一次调用。{@code user} 不知道时为 {@code "-"}；{@code outcome} 是 {@code ok}、{@code failed}、{@code busy}（没等到名额）。 */
    public record Call(Instant at, String user, String plugin, String purpose, String route, String model, long input, long output,
                       long ms, String outcome) {}

    /** 一段时间里一个人、一个插件、一种用途的合计。 */
    public record Total(String user, String plugin, String purpose, int calls, int failures, long input, long output) {}

    private final Connection connection;

    private PluginUsage(Connection connection) {
        this.connection = connection;
    }

    public static PluginUsage open(Path dir) throws IOException, SQLException {
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
                    CREATE TABLE IF NOT EXISTS calls (
                      at      TEXT NOT NULL,
                      user    TEXT NOT NULL,
                      plugin  TEXT NOT NULL,
                      purpose TEXT NOT NULL,
                      route   TEXT,
                      model   TEXT,
                      input   INTEGER NOT NULL,
                      output  INTEGER NOT NULL,
                      ms      INTEGER NOT NULL,
                      outcome TEXT NOT NULL
                    )""");
            statement.execute("CREATE INDEX IF NOT EXISTS calls_by_time ON calls (at)");
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                failure.addSuppressed(ignored);
            }
            throw failure;
        }
        return new PluginUsage(connection);
    }

    public synchronized void record(Call call) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO calls (at, user, plugin, purpose, route, model, input, output, ms, outcome) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, call.at().toString());
            insert.setString(2, call.user());
            insert.setString(3, call.plugin());
            insert.setString(4, call.purpose());
            insert.setString(5, call.route());
            insert.setString(6, call.model());
            insert.setLong(7, call.input());
            insert.setLong(8, call.output());
            insert.setLong(9, call.ms());
            insert.setString(10, call.outcome());
            insert.executeUpdate();
        }
    }

    /** 这个人、这个插件 {@code since} 以后用掉的 token（输入加输出）。 */
    public synchronized long tokens(String user, String plugin, Instant since) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT coalesce(sum(input + output), 0) FROM calls WHERE user = ? AND plugin = ? AND at >= ?")) {
            select.setString(1, user);
            select.setString(2, plugin);
            select.setString(3, since.toString());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    /** {@code since} 以后的合计，按人、插件、用途；token 多的在前。 */
    public synchronized List<Total> totals(Instant since) throws SQLException {
        List<Total> totals = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT user, plugin, purpose, count(*), sum(outcome <> 'ok'), sum(input), sum(output) FROM calls WHERE at >= ?
                GROUP BY user, plugin, purpose ORDER BY sum(input) + sum(output) DESC, user, plugin, purpose""")) {
            select.setString(1, since.toString());
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    totals.add(new Total(rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4), rows.getInt(5),
                            rows.getLong(6), rows.getLong(7)));
                }
            }
        }
        return totals;
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
