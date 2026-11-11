package tantalum.shell.param;

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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.local.fs.PrivateFiles;
import tantalum.shell.auth.UserStore;
import tantalum.shell.json.JsonText;
import tantalum.shell.plugin.Kind;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;

/**
 * 插件参数的值和密码，存在 {@code users.db} 里，不进 journal。值分三份（{@link Scope}）：管理员给所有人的默认、用户自己的、
 * 管理员给某个人指定的。这里只管存取，不懂声明：值合不合格由 {@link ParamRules} 查，用哪一份由 {@link ParamResolver} 定。
 * 两张表很小，整张读进内存，改的时候同时更新。
 */
public final class ParamStore implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ParamStore.class);

    public enum Scope {
        DEFAULT, OWN, ASSIGNED;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 值属于哪里：哪一份、谁的（默认那份没有用户，为空串）、哪个插件。 */
    private record Slot(Scope scope, String user, Kind kind, String name) {}

    private record SecretSlot(String user, Kind kind, String name, String key) {
        String bound() {
            return user + "\n" + kind.key() + "\n" + name + "\n" + key;
        }
    }

    private final Connection connection;
    private final Clock clock;
    private final SecretBox box;
    private final Map<Slot, Map<String, Json>> values = new ConcurrentHashMap<>();
    private final Map<SecretSlot, SecretBox.Sealed> secrets = new ConcurrentHashMap<>();

    private ParamStore(Connection connection, Clock clock, SecretBox box) {
        this.connection = connection;
        this.clock = clock;
        this.box = box;
    }

    /** 不落盘：嵌入使用和测试。 */
    public static ParamStore inMemory(Clock clock, SecretBox box) throws SQLException {
        return create(DriverManager.getConnection("jdbc:sqlite::memory:"), clock, box);
    }

    /** 和用户表同一个文件。 */
    public static ParamStore open(Path dataDir, Clock clock, SecretBox box) throws IOException, SQLException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve(UserStore.FILE);
        if (!Files.exists(file)) {
            PrivateFiles.createFile(file);
        }
        return create(DriverManager.getConnection("jdbc:sqlite:" + file), clock, box);
    }

    private static ParamStore create(Connection connection, Clock clock, SecretBox box) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS plugin_params (
                      scope      TEXT NOT NULL,
                      user       TEXT NOT NULL,
                      kind       TEXT NOT NULL,
                      name       TEXT NOT NULL,
                      key        TEXT NOT NULL,
                      value      TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY (scope, user, kind, name, key)
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS plugin_secrets (
                      user       TEXT NOT NULL,
                      kind       TEXT NOT NULL,
                      name       TEXT NOT NULL,
                      key        TEXT NOT NULL,
                      nonce      BLOB NOT NULL,
                      cipher     BLOB NOT NULL,
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY (user, kind, name, key)
                    )""");
        }
        ParamStore store = new ParamStore(connection, clock, box);
        store.load();
        return store;
    }

    private void load() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT scope, user, kind, name, key, value FROM plugin_params ORDER BY rowid")) {
                while (rows.next()) {
                    Json value = JsonText.parseOrNull(rows.getString(6));
                    try {
                        Slot slot = new Slot(Scope.valueOf(rows.getString(1).toUpperCase(Locale.ROOT)), rows.getString(2),
                                Kind.of(rows.getString(3)), rows.getString(4));
                        if (value != null) {
                            values.computeIfAbsent(slot, ignored -> new LinkedHashMap<>()).put(rows.getString(5), value);
                        }
                    } catch (IllegalArgumentException unknown) {
                        // 以后版本写的种类或份，这个版本不认识，跳过
                    }
                }
            }
            try (ResultSet rows = statement.executeQuery("SELECT user, kind, name, key, nonce, cipher FROM plugin_secrets")) {
                while (rows.next()) {
                    try {
                        secrets.put(new SecretSlot(rows.getString(1), Kind.of(rows.getString(2)), rows.getString(3), rows.getString(4)),
                                new SecretBox.Sealed(rows.getBytes(5), rows.getBytes(6)));
                    } catch (IllegalArgumentException unknown) {
                        // 同上
                    }
                }
            }
        }
        values.replaceAll((slot, map) -> Map.copyOf(map));
    }

    // ---- 普通值 ----

    /** 这一份里这个插件存着的值，没有就是空表。{@code user} 在默认那份里不看，传 null。 */
    public Map<String, Json> values(Scope scope, Principal user, Kind kind, String name) {
        return values.getOrDefault(slot(scope, user, kind, name), Map.of());
    }

    /** 一次保存：{@code put} 里的写上，{@code remove} 里的删掉，在一个事务里。 */
    public synchronized void save(Scope scope, Principal user, Kind kind, String name, Map<String, Json> put, Set<String> remove) {
        Slot slot = slot(scope, user, kind, name);
        try {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement write = connection.prepareStatement(
                        "INSERT OR REPLACE INTO plugin_params (scope, user, kind, name, key, value, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    for (Map.Entry<String, Json> entry : put.entrySet()) {
                        bind(write, slot);
                        write.setString(5, entry.getKey());
                        write.setString(6, JsonText.toText(entry.getValue()));
                        write.setString(7, clock.instant().toString());
                        write.executeUpdate();
                    }
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM plugin_params WHERE scope = ? AND user = ? AND kind = ? AND name = ? AND key = ?")) {
                    for (String key : remove) {
                        bind(delete, slot);
                        delete.setString(5, key);
                        delete.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot save plugin params of " + kind.key() + " " + name, failure);
        }
        Map<String, Json> next = new LinkedHashMap<>(values.getOrDefault(slot, Map.of()));
        next.putAll(put);
        remove.forEach(next::remove);
        if (next.isEmpty()) {
            values.remove(slot);
        } else {
            values.put(slot, Map.copyOf(next));
        }
    }

    /** 删掉这一份里这个插件的全部值。 */
    public void clear(Scope scope, Principal user, Kind kind, String name) {
        save(scope, user, kind, name, Map.of(), values(scope, user, kind, name).keySet());
    }

    // ---- 密码 ----

    public synchronized void putSecret(Principal user, Kind kind, String name, String key, String plain) {
        SecretSlot slot = new SecretSlot(user.value(), kind, name, key);
        SecretBox.Sealed sealed = box.seal(plain, slot.bound());
        try (PreparedStatement write = connection.prepareStatement(
                "INSERT OR REPLACE INTO plugin_secrets (user, kind, name, key, nonce, cipher, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            write.setString(1, slot.user());
            write.setString(2, kind.key());
            write.setString(3, name);
            write.setString(4, key);
            write.setBytes(5, sealed.nonce());
            write.setBytes(6, sealed.cipher());
            write.setString(7, clock.instant().toString());
            write.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot save a plugin secret of " + kind.key() + " " + name, failure);
        }
        secrets.put(slot, sealed);
    }

    public synchronized void clearSecret(Principal user, Kind kind, String name, String key) {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM plugin_secrets WHERE user = ? AND kind = ? AND name = ? AND key = ?")) {
            delete.setString(1, user.value());
            delete.setString(2, kind.key());
            delete.setString(3, name);
            delete.setString(4, key);
            delete.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot clear a plugin secret of " + kind.key() + " " + name, failure);
        }
        secrets.remove(new SecretSlot(user.value(), kind, name, key));
    }

    /** 库里存着这个密码吗（解不解得开不管）。 */
    public boolean hasSecret(Principal user, Kind kind, String name, String key) {
        return secrets.containsKey(new SecretSlot(user.value(), kind, name, key));
    }

    /** 取出原文。没存、或解不开（密钥换了）为空；解不开时记一行日志，不带密文。 */
    public Optional<String> secret(Principal user, Kind kind, String name, String key) {
        SecretSlot slot = new SecretSlot(user.value(), kind, name, key);
        SecretBox.Sealed sealed = secrets.get(slot);
        if (sealed == null) {
            return Optional.empty();
        }
        Optional<String> plain = box.open(sealed, slot.bound());
        if (plain.isEmpty()) {
            LOG.warn("cannot decrypt the secret {} of {} {} for {}: the key changed, the user has to enter it again",
                    key, kind.key(), name, user.value());
        }
        return plain;
    }

    /** 存着但解不开：页面显示“要重新填”。不记日志。 */
    public boolean stale(Principal user, Kind kind, String name, String key) {
        SecretSlot slot = new SecretSlot(user.value(), kind, name, key);
        SecretBox.Sealed sealed = secrets.get(slot);
        return sealed != null && box.open(sealed, slot.bound()).isEmpty();
    }

    private static Slot slot(Scope scope, Principal user, Kind kind, String name) {
        return new Slot(scope, scope == Scope.DEFAULT ? "" : user.value(), kind, name);
    }

    private static void bind(PreparedStatement statement, Slot slot) throws SQLException {
        statement.setString(1, slot.scope().key());
        statement.setString(2, slot.user());
        statement.setString(3, slot.kind().key());
        statement.setString(4, slot.name());
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
