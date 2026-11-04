package tantalum.shell.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tantalum.shell.auth.UserStore;
import tantalum.value.id.Principal;

/**
 * 每个用户自己的设置（compaction-threshold T5）。存在 {@code users.db} 的 {@code user_settings} 表里，一行一项，不进 journal；
 * 现在只有压缩点。按用户缓存在内存里，改的时候先写库再更新缓存。
 */
public final class UserSettings implements AutoCloseable {

    /** 压缩点能选的百分比，100 就是“用满”（T4）。 */
    public static final List<Integer> COMPACTION_PERCENTS = List.of(50, 70, 85, 100);
    public static final int DEFAULT_COMPACTION_PERCENT = 100;

    private static final String COMPACTION_PERCENT = "compaction_percent";

    private final Connection connection;
    private final Clock clock;
    private final Map<Principal, Integer> percents = new ConcurrentHashMap<>();

    private UserSettings(Connection connection, Clock clock) {
        this.connection = connection;
        this.clock = clock;
    }

    /** 不落盘的设置：嵌入使用和测试。 */
    public static UserSettings inMemory(Clock clock) throws SQLException {
        return create(DriverManager.getConnection("jdbc:sqlite::memory:"), clock);
    }

    /** 和用户表、插件开关同一个文件。 */
    public static UserSettings open(Path dataDir, Clock clock) throws IOException, SQLException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve(UserStore.FILE);
        if (!Files.exists(file)) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        return create(DriverManager.getConnection("jdbc:sqlite:" + file), clock);
    }

    private static UserSettings create(Connection connection, Clock clock) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS user_settings (
                      user       TEXT NOT NULL,
                      name       TEXT NOT NULL,
                      value      TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY (user, name)
                    )""");
        }
        return new UserSettings(connection, clock);
    }

    /** 这个用户选的压缩点百分比；没选过是 100。 */
    public int compactionPercent(Principal user) {
        Integer cached = percents.get(user);
        if (cached != null) {
            return cached;
        }
        // 不用 computeIfAbsent：读库要拿对象锁，保存时拿着对象锁写缓存，两边会互等
        int loaded = loadPercent(user);
        Integer raced = percents.putIfAbsent(user, loaded);
        return raced == null ? loaded : raced;
    }

    /** 不是 {@link #COMPACTION_PERCENTS} 之一时抛 {@link IllegalArgumentException}。 */
    public synchronized void setCompactionPercent(Principal user, int percent) {
        if (!COMPACTION_PERCENTS.contains(percent)) {
            throw new IllegalArgumentException("compaction percent must be one of " + COMPACTION_PERCENTS + " but was " + percent);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO user_settings (user, name, value, updated_at) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, user.value());
            statement.setString(2, COMPACTION_PERCENT);
            statement.setString(3, Integer.toString(percent));
            statement.setString(4, clock.instant().toString());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot save settings for " + user.value(), failure);
        }
        percents.put(user, percent);
    }

    private synchronized int loadPercent(Principal user) {
        try (PreparedStatement query = connection.prepareStatement("SELECT value FROM user_settings WHERE user = ? AND name = ?")) {
            query.setString(1, user.value());
            query.setString(2, COMPACTION_PERCENT);
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) {
                    try {
                        int stored = Integer.parseInt(rows.getString(1));
                        // 以后版本可能加别的档位，这个版本不认识的按默认
                        return COMPACTION_PERCENTS.contains(stored) ? stored : DEFAULT_COMPACTION_PERCENT;
                    } catch (NumberFormatException garbled) {
                        return DEFAULT_COMPACTION_PERCENT;
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot read settings for " + user.value(), failure);
        }
        return DEFAULT_COMPACTION_PERCENT;
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
