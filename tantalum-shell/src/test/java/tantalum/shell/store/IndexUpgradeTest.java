package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** waiting-state 任务 7：索引库加了两列，版本 2 → 3。旧库（如 8091 上的）启动时整个重建，内容由会话文件补回。 */
class IndexUpgradeTest {

    @TempDir
    Path temp;

    private Connection raw() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + temp.resolve(SessionIndex.FILE));
    }

    /** 版本 2 的库：没有 answer_deadline 列，还有一行旧数据。 */
    private void writeVersion2() throws Exception {
        try (Connection connection = raw(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE sessions (
                      session TEXT PRIMARY KEY, principal TEXT NOT NULL, title TEXT NOT NULL, last_activity TEXT,
                      run_status TEXT NOT NULL, active_run TEXT, file_offset INTEGER NOT NULL)""");
            statement.execute("INSERT INTO sessions VALUES ('s1', 'alice', 'old', '2026-11-01T00:00:00Z', 'idle', NULL, 0)");
            statement.execute("PRAGMA user_version=2");
        }
    }

    @Test
    void anOlderIndexIsRebuiltWithTheNewColumns() throws Exception {
        writeVersion2();
        try (SessionIndex index = SessionIndex.open(temp)) {
            assertTrue(index.awaitingWithDeadline().isEmpty(), "the new columns are queryable");
            assertTrue(index.sessions(new tantalum.value.id.Principal("alice")).isEmpty(), "old rows are gone; catch-up rewrites them");
        }
        try (Connection connection = raw(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            assertEquals(SessionIndex.VERSION, result.getInt(1));
        }
    }
}
