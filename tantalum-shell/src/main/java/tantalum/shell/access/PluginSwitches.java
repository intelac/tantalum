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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import tantalum.shell.auth.UserStore;
import tantalum.value.id.Principal;

/**
 * 每个用户自己的插件开关（core-tools C12、C15）。存在 {@code users.db} 的 {@code plugin_settings} 表里，不进 journal；
 * 只存用户动过的项（开或关），没动过的用这一项的默认值：一般是开，策略类插件是关（{@code KernelHook.defaultEnabled()}）。
 * 加 {@code enabled} 列之前的库里只有关掉的项，新加的列对旧行默认为 0，意思不变。按用户缓存在内存里，改的时候同时更新。
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
    private final Map<Principal, Map<Key, Boolean>> chosen = new ConcurrentHashMap<>();

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
                      enabled    INTEGER NOT NULL DEFAULT 0,
                      PRIMARY KEY (user, kind, name)
                    )""");
            if (!hasEnabledColumn(statement)) {
                statement.execute("ALTER TABLE plugin_settings ADD COLUMN enabled INTEGER NOT NULL DEFAULT 0");
            }
        }
        return new PluginSwitches(connection, clock);
    }

    private static boolean hasEnabledColumn(Statement statement) throws SQLException {
        try (ResultSet columns = statement.executeQuery("PRAGMA table_info(plugin_settings)")) {
            while (columns.next()) {
                if (columns.getString("name").equals("enabled")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 这个用户关掉的项（没动过、默认关的不算）。 */
    public Set<Key> off(Principal user) {
        Set<Key> keys = new HashSet<>();
        choices(user).forEach((key, enabled) -> {
            if (!enabled) {
                keys.add(key);
            }
        });
        return Set.copyOf(keys);
    }

    private Map<Key, Boolean> choices(Principal user) {
        Map<Key, Boolean> cached = chosen.get(user);
        if (cached != null) {
            return cached;
        }
        Map<Key, Boolean> loaded = load(user);
        Map<Key, Boolean> raced = chosen.putIfAbsent(user, loaded);
        return raced == null ? loaded : raced;
    }

    /** 默认开的项。 */
    public boolean enabled(Principal user, Kind kind, String name) {
        return enabled(user, kind, name, true);
    }

    /** 用户动过就按他的，没动过用 {@code byDefault}。 */
    public boolean enabled(Principal user, Kind kind, String name, boolean byDefault) {
        return choices(user).getOrDefault(new Key(kind, name), byDefault);
    }

    /** 开和关都记下来：默认关的插件，用户打开后要记得是开的。 */
    public synchronized void set(Principal user, Kind kind, String name, boolean enabled) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO plugin_settings (user, kind, name, updated_at, enabled) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, user.value());
            statement.setString(2, kind.key());
            statement.setString(3, name);
            statement.setString(4, clock.instant().toString());
            statement.setInt(5, enabled ? 1 : 0);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot save plugin settings for " + user.value(), failure);
        }
        chosen.put(user, load(user));
    }

    private synchronized Map<Key, Boolean> load(Principal user) {
        Map<Key, Boolean> keys = new HashMap<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT kind, name, enabled FROM plugin_settings WHERE user = ?")) {
            query.setString(1, user.value());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    try {
                        keys.put(new Key(Kind.of(rows.getString(1)), rows.getString(2)), rows.getInt(3) != 0);
                    } catch (IllegalArgumentException unknownKind) {
                        // 以后版本写的种类，这个版本不认识，跳过
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot read plugin settings for " + user.value(), failure);
        }
        return Map.copyOf(keys);
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
