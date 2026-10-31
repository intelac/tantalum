package tantalum.shell.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteSpikeTest {

    @TempDir
    Path dir;

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("journal.db"));
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
        }
        return connection;
    }

    private static String pragma(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA " + name)) {
            result.next();
            return result.getString(1);
        }
    }

    private static int count(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count(*) FROM events")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void insert(Connection connection, int from, int to) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO events(seq, payload) VALUES (?, ?)")) {
            for (int seq = from; seq < to; seq++) {
                insert.setInt(1, seq);
                insert.setString(2, "{}");
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    @Test
    void walAndFullSyncAreActive() throws Exception {
        try (Connection connection = open()) {
            assertEquals("wal", pragma(connection, "journal_mode"));
            assertEquals("2", pragma(connection, "synchronous"));
        }
    }

    @Test
    void onlyCommittedBatchesSurviveReopen() throws Exception {
        try (Connection connection = open()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE events (seq INTEGER PRIMARY KEY, payload TEXT NOT NULL)");
            }
            connection.setAutoCommit(false);
            insert(connection, 0, 100);
            connection.commit();
            insert(connection, 100, 150);
            connection.rollback();
        }

        try (Connection connection = open()) {
            assertEquals(100, count(connection));
            assertEquals("ok", pragma(connection, "quick_check"));
        }
    }
}
