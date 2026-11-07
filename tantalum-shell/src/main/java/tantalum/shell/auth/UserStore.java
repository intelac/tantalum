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
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import tantalum.local.fs.PrivateFiles;
import tantalum.value.id.Principal;
import tantalum.value.id.ReservedName;

/**
 * 用户表（{@code users.db}）：口令用 Argon2id 哈希；{@code admin} 列标管理员（ops-hardening-2 需求 2）；同一个库里还有登录表（{@link Logins}）。
 * 版本用 {@code PRAGMA user_version}：1 是只有 users 表的旧库，2 加 {@code admin} 列和 {@code logins} 表。用户库不能重建，只 {@code ALTER}。
 */
public final class UserStore implements AutoCloseable {

    public static final String FILE = "users.db";
    static final int VERSION = 2;
    private static final Logger LOG = LoggerFactory.getLogger(UserStore.class);

    private final Connection connection;
    private final Logins logins;
    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    /** 用户不存在时也做一次哈希比较，避免从响应时间判断用户名是否存在。 */
    private final String decoyHash = encoder.encode("decoy");

    private UserStore(Connection connection) {
        this.connection = connection;
        this.logins = new Logins(connection);
    }

    public static UserStore open(Path dir) throws IOException, SQLException {
        Files.createDirectories(dir);
        Path file = dir.resolve(FILE);
        if (!Files.exists(file)) {
            PrivateFiles.createFile(file);
        }
        PrivateFiles.restrict(file);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                      name          TEXT PRIMARY KEY,
                      password_hash TEXT NOT NULL,
                      disabled       INTEGER NOT NULL DEFAULT 0,
                      created_at     TEXT NOT NULL,
                      password_stamp INTEGER NOT NULL DEFAULT 0,
                      admin          INTEGER NOT NULL DEFAULT 0
                    )""");
            upgrade(statement);
            Logins.createTable(statement);
            statement.execute("PRAGMA user_version=" + VERSION);
        }
        return new UserStore(connection);
    }

    /** 版本 1 的库没有 {@code admin} 列；加上以后如果一个管理员都没有，最早创建的账号成为管理员，否则没人能管。 */
    private static void upgrade(Statement statement) throws SQLException {
        int version;
        try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            version = result.getInt(1);
        }
        if (version >= VERSION) {
            return;
        }
        boolean hasAdminColumn;
        try (ResultSet columns = statement.executeQuery("PRAGMA table_info(users)")) {
            hasAdminColumn = false;
            while (columns.next()) {
                hasAdminColumn |= "admin".equals(columns.getString("name"));
            }
        }
        if (!hasAdminColumn) {
            statement.execute("ALTER TABLE users ADD COLUMN admin INTEGER NOT NULL DEFAULT 0");
        }
        try (ResultSet admins = statement.executeQuery("SELECT count(*) FROM users WHERE admin = 1")) {
            admins.next();
            if (admins.getLong(1) == 0) {
                int promoted = statement.executeUpdate(
                        "UPDATE users SET admin = 1 WHERE name = (SELECT name FROM users ORDER BY created_at, name LIMIT 1)");
                if (promoted > 0) {
                    try (ResultSet who = statement.executeQuery("SELECT name FROM users WHERE admin = 1")) {
                        who.next();
                        LOG.info("users.db upgraded to version {}: {} is now the administrator (the earliest account)", VERSION, who.getString(1));
                    }
                }
            }
        }
    }

    /** 用户名不能用、已经有了、没有这个用户、最后一个管理员不能停用或取消。命令行按种类换成用户的语言（{@code UserCommand}）；消息是英文，给日志。 */
    public static final class Refused extends IllegalArgumentException {

        public enum Kind { BAD_NAME, RESERVED_NAME, EXISTS, NO_SUCH, LAST_ADMIN }

        private final Kind kind;
        private final String name;

        Refused(Kind kind, String name, String message) {
            super(message);
            this.kind = kind;
            this.name = name;
        }

        public Kind kind() {
            return kind;
        }

        public String name() {
            return name;
        }
    }

    /** 列表和接口里的一个账号。 */
    public record Account(String name, boolean admin, boolean disabled, Instant createdAt) {}

    public Logins logins() {
        return logins;
    }

    public synchronized void add(String name, String password) throws SQLException {
        add(name, password, false);
    }

    public synchronized void add(String name, String password, boolean admin) throws SQLException {
        if (ReservedName.matches(name)) {
            throw new Refused(Refused.Kind.RESERVED_NAME, name, name + " is reserved by Windows and cannot be a user name.");
        }
        if (!Principal.isUsable(name)) {
            throw new Refused(Refused.Kind.BAD_NAME, name, "user name may only contain a-z, 0-9, - and _: " + name);
        }
        if (find(name).isPresent()) {
            throw new Refused(Refused.Kind.EXISTS, name, "user already exists: " + name);
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO users (name, password_hash, created_at, admin) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, name);
            insert.setString(2, encoder.encode(password));
            insert.setString(3, Instant.now().toString());
            insert.setInt(4, admin ? 1 : 0);
            insert.executeUpdate();
        }
    }

    /** 用户不存在、已停用或密码错误都返回 false。 */
    public synchronized boolean verify(String name, String password) throws SQLException {
        Optional<Row> row = find(name);
        // 用户不存在时也算一次哈希，响应时间不暴露用户名是否存在。
        boolean matches = encoder.matches(password, row.map(Row::hash).orElse(decoyHash));
        return matches && row.isPresent() && !row.get().disabled();
    }

    /** 口令戳随之变化，之前的登录在下一次请求或下一个心跳时失效。 */
    public synchronized void resetPassword(String name, String password) throws SQLException {
        update("UPDATE users SET password_hash = ?, password_stamp = password_stamp + 1 WHERE name = ?", encoder.encode(password), name);
    }

    /** 登录是否还有效要看的两件事，加上是不是管理员。{@code stamp} 每次改密码加一。 */
    public record Status(boolean disabled, long stamp, boolean admin) {}

    /** 用户不存在时为空。每次都读库，所以命令行在服务外的修改立即可见。 */
    public synchronized Optional<Status> status(String name) throws SQLException {
        return find(name).map(row -> new Status(row.disabled(), row.stamp(), row.admin()));
    }

    private record Row(String hash, boolean disabled, long stamp, boolean admin) {}

    private Optional<Row> find(String name) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT password_hash, disabled, password_stamp, admin FROM users WHERE name = ?")) {
            query.setString(1, name);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? Optional.of(new Row(row.getString(1), row.getInt(2) != 0, row.getLong(3), row.getInt(4) != 0))
                        : Optional.empty();
            }
        }
    }

    /** 全部账号，按创建时间。 */
    public synchronized List<Account> list() throws SQLException {
        List<Account> accounts = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT name, admin, disabled, created_at FROM users ORDER BY created_at, name")) {
            while (row.next()) {
                accounts.add(new Account(row.getString(1), row.getInt(2) != 0, row.getInt(3) != 0, Instant.parse(row.getString(4))));
            }
        }
        return accounts;
    }

    /** 停用。停用后不能让系统没有一个启用的管理员（需求 2.4）。 */
    public synchronized void disable(String name) throws SQLException {
        requireAnotherActiveAdmin(name);
        update("UPDATE users SET disabled = ? WHERE name = ?", "1", name);
    }

    public synchronized void enable(String name) throws SQLException {
        update("UPDATE users SET disabled = ? WHERE name = ?", "0", name);
    }

    /** 设为或取消管理员。取消时不能让系统没有一个启用的管理员。 */
    public synchronized void setAdmin(String name, boolean admin) throws SQLException {
        if (!admin) {
            requireAnotherActiveAdmin(name);
        }
        update("UPDATE users SET admin = ? WHERE name = ?", admin ? "1" : "0", name);
    }

    /** 除了 {@code name} 以外还得有一个启用的管理员；{@code name} 不是启用的管理员时不限制。 */
    private void requireAnotherActiveAdmin(String name) throws SQLException {
        Optional<Row> row = find(name);
        if (row.isEmpty()) {
            throw new Refused(Refused.Kind.NO_SUCH, name, "no such user: " + name);
        }
        if (!row.get().admin() || row.get().disabled()) {
            return;
        }
        try (PreparedStatement query = connection.prepareStatement("SELECT count(*) FROM users WHERE admin = 1 AND disabled = 0 AND name <> ?")) {
            query.setString(1, name);
            try (ResultSet count = query.executeQuery()) {
                count.next();
                if (count.getLong(1) == 0) {
                    throw new Refused(Refused.Kind.LAST_ADMIN, name, name + " is the last administrator and cannot be disabled or demoted");
                }
            }
        }
    }

    public synchronized long count() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT count(*) FROM users")) {
            row.next();
            return row.getLong(1);
        }
    }

    private void update(String sql, String value, String name) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, value);
            update.setString(2, name);
            if (update.executeUpdate() == 0) {
                throw new Refused(Refused.Kind.NO_SUCH, name, "no such user: " + name);
            }
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
