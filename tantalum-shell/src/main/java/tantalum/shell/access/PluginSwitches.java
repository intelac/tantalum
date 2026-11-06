package tantalum.shell.access;

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
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import tantalum.shell.auth.UserStore;
import tantalum.value.id.Principal;

/**
 * 每个用户自己的插件开关（core-tools C12、C15）。存在 {@code users.db} 的 {@code plugin_settings} 表里，不进 journal；
 * 默认全开，只存关掉的项。按用户缓存在内存里，改的时候同时更新。
 */
public final class PluginSwitches implements AutoCloseable {

    /**
     * 开关的种类：一个工具、一个 MCP 服务器（关掉等于关掉它的全部工具）、一个钩子插件、本机工作区的总开关或子开关
     * （名字见 {@link LocalAccess#SWITCHES}）。
     */
    public enum Kind {
        TOOL, MCP, PLUGIN, LOCAL;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** 不认识时抛 {@link IllegalArgumentException}。 */
        public static Kind of(String key) {
            return valueOf(key.toUpperCase(Locale.ROOT));
        }
    }

    public record Key(Kind kind, String name) {}

    private final Connection connection;
    private final Clock clock;
    private final Map<Principal, Set<Key>> off = new ConcurrentHashMap<>();

    private PluginSwitches(Connection connection, Clock clock) {
        this.connection = connection;
        this.clock = clock;
    }

    /** 不落盘的开关：嵌入使用和测试，没有用户自己的设置时。 */
    public static PluginSwitches inMemory(Clock clock) throws SQLException {
        return create(DriverManager.getConnection("jdbc:sqlite::memory:"), clock);
    }

    /** 和用户表同一个文件；用户表的命令行工具在服务外改库时，SQLite 的锁让两边排队。 */
    public static PluginSwitches open(Path dataDir, Clock clock) throws IOException, SQLException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve(UserStore.FILE);
        if (!Files.exists(file)) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        return create(DriverManager.getConnection("jdbc:sqlite:" + file), clock);
    }

    private static PluginSwitches create(Connection connection, Clock clock) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS plugin_settings (
                      user       TEXT NOT NULL,
                      kind       TEXT NOT NULL,
                      name       TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY (user, kind, name)
                    )""");
        }
        return new PluginSwitches(connection, clock);
    }

    /** 这个用户关掉的项。 */
    public Set<Key> off(Principal user) {
        Set<Key> cached = off.get(user);
        if (cached != null) {
            return cached;
        }
        Set<Key> loaded = load(user);
        Set<Key> raced = off.putIfAbsent(user, loaded);
        return raced == null ? loaded : raced;
    }

    public boolean enabled(Principal user, Kind kind, String name) {
        return !off(user).contains(new Key(kind, name));
    }

    public synchronized void set(Principal user, Kind kind, String name, boolean enabled) {
        try (PreparedStatement statement = connection.prepareStatement(enabled
                ? "DELETE FROM plugin_settings WHERE user = ? AND kind = ? AND name = ?"
                : "INSERT OR REPLACE INTO plugin_settings (user, kind, name, updated_at) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, user.value());
            statement.setString(2, kind.key());
            statement.setString(3, name);
            if (!enabled) {
                statement.setString(4, clock.instant().toString());
            }
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot save plugin settings for " + user.value(), failure);
        }
        off.put(user, load(user));
    }

    private synchronized Set<Key> load(Principal user) {
        Set<Key> keys = new HashSet<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT kind, name FROM plugin_settings WHERE user = ?")) {
            query.setString(1, user.value());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    try {
                        keys.add(new Key(Kind.of(rows.getString(1)), rows.getString(2)));
                    } catch (IllegalArgumentException unknownKind) {
                        // 以后版本写的种类，这个版本不认识，跳过
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot read plugin settings for " + user.value(), failure);
        }
        return Set.copyOf(keys);
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
