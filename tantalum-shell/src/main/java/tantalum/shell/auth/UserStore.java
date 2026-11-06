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
import java.util.Optional;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import tantalum.local.fs.PrivateFiles;
import tantalum.value.id.Principal;
import tantalum.value.id.ReservedName;

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
                      password_stamp INTEGER NOT NULL DEFAULT 0
                    )""");
        }
        return new UserStore(connection);
    }

    /** 用户名不能用、已经有了、没有这个用户。命令行按种类换成用户的语言（{@code UserCommand}）；消息是英文，给日志。 */
    public static final class Refused extends IllegalArgumentException {

        public enum Kind { BAD_NAME, RESERVED_NAME, EXISTS, NO_SUCH }

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

    public synchronized void add(String name, String password) throws SQLException {
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
                "INSERT INTO users (name, password_hash, created_at) VALUES (?, ?, ?)")) {
            insert.setString(1, name);
            insert.setString(2, encoder.encode(password));
            insert.setString(3, Instant.now().toString());
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

    /** 登录是否还有效要看的两件事。{@code stamp} 每次改密码加一。 */
    public record Status(boolean disabled, long stamp) {}

    /** 用户不存在时为空。每次都读库，所以命令行在服务外的修改立即可见。 */
    public synchronized Optional<Status> status(String name) throws SQLException {
        return find(name).map(row -> new Status(row.disabled(), row.stamp()));
    }

    private record Row(String hash, boolean disabled, long stamp) {}

    private Optional<Row> find(String name) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT password_hash, disabled, password_stamp FROM users WHERE name = ?")) {
            query.setString(1, name);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? Optional.of(new Row(row.getString(1), row.getInt(2) != 0, row.getLong(3))) : Optional.empty();
            }
        }
    }

    public synchronized void disable(String name) throws SQLException {
        update("UPDATE users SET disabled = ? WHERE name = ?", "1", name);
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
