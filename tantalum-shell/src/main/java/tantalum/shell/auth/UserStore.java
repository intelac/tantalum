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
 * Argon2 慢（几十毫秒），哈希和比对都在锁外算，锁里只有读写库（review-fixes 需求 11.4）；改几件事要么全成要么全不成的用一个事务。
 */
public final class UserStore implements AutoCloseable {

    public static final String FILE = "users.db";
    /** 密码至少这么长（review-fixes 需求 11.13）。在入口（接口、命令行、首个管理员）校验，存储层不管：测试和旧库里有更短的。 */
    public static final int MIN_PASSWORD = 8;
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
        } catch (SQLException | RuntimeException failure) {
            // 建表失败时连接不能留着：文件句柄和锁会一直占到进程结束
            try {
                connection.close();
            } catch (SQLException ignored) {
                failure.addSuppressed(ignored);
            }
            throw failure;
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

    public void add(String name, String password) throws SQLException {
        add(name, password, false);
    }

    public void add(String name, String password, boolean admin) throws SQLException {
        if (ReservedName.matches(name)) {
            throw new Refused(Refused.Kind.RESERVED_NAME, name, name + " is reserved by Windows and cannot be a user name.");
        }
        if (!Principal.isUsable(name)) {
            throw new Refused(Refused.Kind.BAD_NAME, name, "user name may only contain a-z, 0-9, - and _: " + name);
        }
        String hash = encoder.encode(password);
        synchronized (this) {
            if (find(name).isPresent()) {
                throw new Refused(Refused.Kind.EXISTS, name, "user already exists: " + name);
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO users (name, password_hash, created_at, admin) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, name);
                insert.setString(2, hash);
                insert.setString(3, Instant.now().toString());
                insert.setInt(4, admin ? 1 : 0);
                insert.executeUpdate();
            }
        }
    }

    /** 用户不存在、已停用或密码错误都返回 false。 */
    public boolean verify(String name, String password) throws SQLException {
        Optional<Row> row;
        synchronized (this) {
            row = find(name);
        }
        // 用户不存在时也算一次哈希，响应时间不暴露用户名是否存在。
        boolean matches = encoder.matches(password, row.map(Row::hash).orElse(decoyHash));
        return matches && row.isPresent() && !row.get().disabled();
    }

    /** 口令戳随之变化，之前的登录在下一次请求或下一个心跳时失效。 */
    public void resetPassword(String name, String password) throws SQLException {
        String hash = encoder.encode(password);
        synchronized (this) {
            update("UPDATE users SET password_hash = ?, password_stamp = password_stamp + 1 WHERE name = ?", hash, name);
        }
    }

    /**
     * 自己改密码（review-fixes 需求 11.1）：换哈希和口令戳、{@code keepLoginId} 这条登录记上新戳、其余登录删掉，一个事务里完成；
     * 返回删掉的登录 id，调用方按它们断开推送连接。{@code keepLoginId} 为 null 时全部登录都删。
     */
    public List<String> changePassword(String name, String password, String keepLoginId) throws SQLException {
        String hash = encoder.encode(password);
        synchronized (this) {
            return transaction(() -> {
                update("UPDATE users SET password_hash = ?, password_stamp = password_stamp + 1 WHERE name = ?", hash, name);
                long stamp = find(name).map(Row::stamp).orElse(-1L);
                if (keepLoginId != null) {
                    logins.restamp(keepLoginId, stamp);
                }
                return logins.deleteOthers(name, keepLoginId);
            });
        }
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

    /**
     * 除了这个账号以外还有一个启用的管理员，或者这个账号本来就不是启用的管理员，才改得动。写在同一条 UPDATE 里，
     * 两个管理员互相取消时不会双双成功（review-fixes 需求 11.4）。
     */
    private static final String ANOTHER_ADMIN = " AND (admin = 0 OR disabled = 1"
            + " OR EXISTS (SELECT 1 FROM users other WHERE other.admin = 1 AND other.disabled = 0 AND other.name <> users.name))";

    /** 停用。停用后不能让系统没有一个启用的管理员（需求 2.4）。 */
    public synchronized void disable(String name) throws SQLException {
        guarded("UPDATE users SET disabled = 1 WHERE name = ?" + ANOTHER_ADMIN, name);
    }

    public synchronized void enable(String name) throws SQLException {
        update("UPDATE users SET disabled = ? WHERE name = ?", "0", name);
    }

    /** 设为或取消管理员。取消时不能让系统没有一个启用的管理员。 */
    public synchronized void setAdmin(String name, boolean admin) throws SQLException {
        if (admin) {
            update("UPDATE users SET admin = ? WHERE name = ?", "1", name);
        } else {
            guarded("UPDATE users SET admin = 0 WHERE name = ?" + ANOTHER_ADMIN, name);
        }
    }

    /** 接口的一次修改：给了哪个改哪个，一个事务，有一样不行就都不改。 */
    public synchronized void change(String name, Boolean admin, Boolean disabled) throws SQLException {
        transaction(() -> {
            if (admin != null) {
                setAdmin(name, admin);
            }
            if (disabled != null) {
                if (disabled) {
                    disable(name);
                } else {
                    enable(name);
                }
            }
            return null;
        });
    }

    /** 带“还有别的管理员”条件的 UPDATE：0 行时分不存在和最后一个管理员。 */
    private void guarded(String sql, String name) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, name);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        if (find(name).isEmpty()) {
            throw new Refused(Refused.Kind.NO_SUCH, name, "no such user: " + name);
        }
        throw new Refused(Refused.Kind.LAST_ADMIN, name, name + " is the last administrator and cannot be disabled or demoted");
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

    private interface Work<T> {
        T run() throws SQLException;
    }

    /**
     * 一个事务。连接是用户表和登录表共用的，所以两把锁都拿着（先用户表后登录表，到处都是这个顺序），别的线程的单条语句不会混进来。
     * 抛出去的无论是 SQL 错误还是 {@link Refused} 都回滚。
     */
    private <T> T transaction(Work<T> work) throws SQLException {
        synchronized (logins) {
            connection.setAutoCommit(false);
            boolean committed = false;
            try {
                T result = work.run();
                connection.commit();
                committed = true;
                return result;
            } finally {
                try {
                    if (!committed) {
                        connection.rollback();
                    }
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
