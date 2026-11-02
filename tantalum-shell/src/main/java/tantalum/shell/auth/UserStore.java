package tantalum.shell.auth;

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
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import tantalum.value.id.Principal;

/** 用户表。单独的 SQLite 文件，不进 journal；密码只存 Argon2id 哈希。 */
public final class UserStore implements AutoCloseable {

    public static final String FILE = "users.db";

    private final Connection connection;
    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    /** 用户不存在时也做一次哈希比较，避免从响应时间判断用户名是否存在。 */
    private final String decoyHash = encoder.encode("decoy");

    private UserStore(Connection connection) {
        this.connection = connection;
    }

    public static UserStore open(Path dir) throws IOException, SQLException {
        Files.createDirectories(dir);
        Path file = dir.resolve(FILE);
        if (!Files.exists(file)) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                      name          TEXT PRIMARY KEY,
                      password_hash TEXT NOT NULL,
                      disabled      INTEGER NOT NULL DEFAULT 0,
                      created_at    TEXT NOT NULL
                    )""");
            if (!hasColumn(connection, "password_stamp")) {
                statement.execute("ALTER TABLE users ADD COLUMN password_stamp INTEGER NOT NULL DEFAULT 0");
            }
        }
        return new UserStore(connection);
    }

    public synchronized void add(String name, String password) throws SQLException {
        if (!Principal.isUsable(name)) {
            throw new IllegalArgumentException("user name may only contain a-z, 0-9, - and _: " + name);
        }
        if (hashOf(name) != null) {
            throw new IllegalArgumentException("user already exists: " + name);
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO users (name, password_hash, created_at) VALUES (?, ?, ?)")) {
            insert.setString(1, name);
            insert.setString(2, encoder.encode(password));
            insert.setString(3, Instant.now().toString());
            insert.executeUpdate();
        }
    }

    /** 用户不存在、已停用或密码错误都返回 false。 */
    public synchronized boolean verify(String name, String password) throws SQLException {
        String hash = hashOf(name);
        boolean matches = encoder.matches(password, hash == null ? decoyHash : hash);
        return matches && hash != null && !isDisabled(name);
    }

    /** 口令戳随之变化，之前的登录在下一次请求或下一个心跳时失效。 */
    public synchronized void resetPassword(String name, String password) throws SQLException {
        update("UPDATE users SET password_hash = ?, password_stamp = password_stamp + 1 WHERE name = ?", encoder.encode(password), name);
    }

    /** 登录是否还有效要看的两件事。{@code stamp} 每次改密码加一。 */
    public record Status(boolean disabled, long stamp) {}

    /** 用户不存在时为空。每次都读库，所以命令行在服务外的修改立即可见。 */
    public synchronized Optional<Status> status(String name) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT disabled, password_stamp FROM users WHERE name = ?")) {
            query.setString(1, name);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? Optional.of(new Status(row.getInt(1) != 0, row.getLong(2))) : Optional.empty();
            }
        }
    }

    private static boolean hasColumn(Connection connection, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet columns = statement.executeQuery("PRAGMA table_info(users)")) {
            while (columns.next()) {
                if (column.equals(columns.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    public synchronized void disable(String name) throws SQLException {
        update("UPDATE users SET disabled = ? WHERE name = ?", "1", name);
    }

    public synchronized boolean isDisabled(String name) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT disabled FROM users WHERE name = ?")) {
            query.setString(1, name);
            try (ResultSet row = query.executeQuery()) {
                return row.next() && row.getInt(1) != 0;
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

    /** 不存在时返回 null。 */
    synchronized String hashOf(String name) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT password_hash FROM users WHERE name = ?")) {
            query.setString(1, name);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }

    private void update(String sql, String value, String name) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, value);
            update.setString(2, name);
            if (update.executeUpdate() == 0) {
                throw new IllegalArgumentException("no such user: " + name);
            }
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
