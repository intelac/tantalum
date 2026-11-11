package tantalum.shell.access;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import tantalum.local.fs.PrivateFiles;
import tantalum.shell.auth.UserStore;
import tantalum.shell.plugin.Kind;
import tantalum.value.id.Principal;

/**
 * 每个用户自己的插件开关。存在 {@code users.db} 的 {@code plugin_settings} 表里，不进 journal；
 * 只存用户动过的项（开或关），没动过的用这一项的默认值：一般是开，策略类插件是关（{@code KernelHook.defaultEnabled()}）。
 * 加 {@code enabled} 列之前的库里只有关掉的项，新加的列对旧行默认为 0，意思不变。按用户缓存在内存里，改的时候同时更新。
 *
 * <p>管理员可以给一项定所有人的默认，还可以锁住（{@link Policy}，表 {@code plugin_defaults}）。一项开没开：
 * 先算底子（管理员定了默认就用它，没定用这一项自带的默认）；锁着就是底子，用户的选择留着但不起作用；没锁，用户动过按用户的。
 */
public final class PluginSwitches implements AutoCloseable {

    public record Key(Kind kind, String name) {}

    /** 管理员对一项的安排。{@code enabled} 为 null 是不改自带的默认；{@code reason} 是锁住时给用户看的一句话，可以为空。 */
    public record Policy(Boolean enabled, boolean locked, String reason) {}

    private final Connection connection;
    private final Clock clock;
    private final Map<Principal, Map<Key, Boolean>> chosen = new ConcurrentHashMap<>();
    private final Map<Key, Policy> policies = new ConcurrentHashMap<>();

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
            PrivateFiles.createFile(file);
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
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS plugin_defaults (
                      kind       TEXT NOT NULL,
                      name       TEXT NOT NULL,
                      enabled    INTEGER,
                      locked     INTEGER NOT NULL DEFAULT 0,
                      reason     TEXT NOT NULL DEFAULT '',
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY (kind, name)
                    )""");
        }
        PluginSwitches switches = new PluginSwitches(connection, clock);
        switches.loadPolicies();
        return switches;
    }

    private void loadPolicies() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT kind, name, enabled, locked, reason FROM plugin_defaults")) {
            while (rows.next()) {
                int enabled = rows.getInt(3);
                Boolean byAdmin = rows.wasNull() ? null : enabled != 0;
                try {
                    policies.put(new Key(Kind.of(rows.getString(1)), rows.getString(2)),
                            new Policy(byAdmin, rows.getInt(4) != 0, rows.getString(5)));
                } catch (IllegalArgumentException unknownKind) {
                    // 以后版本写的种类，这个版本不认识，跳过
                }
            }
        }
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

    /** {@code byDefault} 是这一项自带的默认。管理员的默认和锁定在这里生效，所有问开关的地方都走这里。 */
    public boolean enabled(Principal user, Kind kind, String name, boolean byDefault) {
        Key key = new Key(kind, name);
        Policy policy = policies.get(key);
        boolean base = policy != null && policy.enabled() != null ? policy.enabled() : byDefault;
        if (policy != null && policy.locked()) {
            return base;
        }
        return choices(user).getOrDefault(key, base);
    }

    /** 用户自己选过的，没动过为空。锁着的时候也照实返回。 */
    public Optional<Boolean> chosen(Principal user, Kind kind, String name) {
        return Optional.ofNullable(choices(user).get(new Key(kind, name)));
    }

    /** 管理员对这一项的安排，没管过为空。 */
    public Optional<Policy> policy(Kind kind, String name) {
        return Optional.ofNullable(policies.get(new Key(kind, name)));
    }

    /** 管理员定默认、锁定。既不改默认又不锁，等于没管过：删掉这一行。用户各自的选择不动，解锁后照旧。 */
    public synchronized void setPolicy(Kind kind, String name, Boolean enabled, boolean locked, String reason) {
        Key key = new Key(kind, name);
        if (enabled == null && !locked) {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM plugin_defaults WHERE kind = ? AND name = ?")) {
                delete.setString(1, kind.key());
                delete.setString(2, name);
                delete.executeUpdate();
            } catch (SQLException failure) {
                throw new IllegalStateException("cannot save the default of " + kind.key() + " " + name, failure);
            }
            policies.remove(key);
            return;
        }
        String why = locked && reason != null ? reason.strip() : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO plugin_defaults (kind, name, enabled, locked, reason, updated_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, kind.key());
            statement.setString(2, name);
            if (enabled == null) {
                statement.setNull(3, java.sql.Types.INTEGER);
            } else {
                statement.setInt(3, enabled ? 1 : 0);
            }
            statement.setInt(4, locked ? 1 : 0);
            statement.setString(5, why);
            statement.setString(6, clock.instant().toString());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot save the default of " + kind.key() + " " + name, failure);
        }
        policies.put(key, new Policy(enabled, locked, why));
    }

    /** 忘掉用户对这一项的选择，回到默认。 */
    public synchronized void clear(Principal user, Kind kind, String name) {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM plugin_settings WHERE user = ? AND kind = ? AND name = ?")) {
            delete.setString(1, user.value());
            delete.setString(2, kind.key());
            delete.setString(3, name);
            delete.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot save plugin settings for " + user.value(), failure);
        }
        chosen.put(user, load(user));
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
