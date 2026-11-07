package tantalum.shell.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * 登录记录：{@code users.db} 里的 {@code logins} 表，代替 Tomcat 的内存会话，重启、崩溃都不丢。
 * Cookie 里只放随机令牌（32 字节，Base64url），表里只存它的 SHA-256；{@code id} 就是这个哈希，也是 SSE 按登录断开用的登录 id。
 * 有效期按最后访问时间算（{@link #check}），再加一个从创建起算的绝对上限，调用方控制多久写一次最后访问时间。
 */
public final class Logins {

    /** 一条有效的登录。 */
    public record Login(String id, String name, long stamp, long lastSeen) {}

    /** 新登录：令牌给 Cookie，id 给订阅。 */
    public record Issued(String token, String id) {}

    private static final int TOKEN_BYTES = 32;

    private final Connection connection;
    private final SecureRandom random = new SecureRandom();

    Logins(Connection connection) {
        this.connection = connection;
    }

    static void createTable(java.sql.Statement statement) throws SQLException {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS logins (
                  id         TEXT PRIMARY KEY,
                  name       TEXT NOT NULL,
                  stamp      INTEGER NOT NULL,
                  created_at TEXT NOT NULL,
                  last_seen  INTEGER NOT NULL
                )""");
        statement.execute("CREATE INDEX IF NOT EXISTS logins_name ON logins (name)");
    }

    /** 令牌的哈希，Base64url，不带填充。 */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public synchronized Issued create(String name, long stamp, long now) throws SQLException {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String id = hash(token);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO logins (id, name, stamp, created_at, last_seen) VALUES (?, ?, ?, ?, ?)")) {
            insert.setString(1, id);
            insert.setString(2, name);
            insert.setLong(3, stamp);
            insert.setString(4, Instant.ofEpochMilli(now).toString());
            insert.setLong(5, now);
            insert.executeUpdate();
        }
        return new Issued(token, id);
    }

    /**
     * 令牌对应的登录：不存在、最后访问距今超过 {@code timeout}、或创建距今超过 {@code maxAge} 都为空。
     * 不查用户状态，那是调用方的事。
     */
    public synchronized Optional<Login> check(String token, long now, Duration timeout, Duration maxAge) throws SQLException {
        return byId(hash(token), now, timeout, maxAge);
    }

    /** 同 {@link #check}，按登录 id（SSE 的心跳用，手里只有 id）。 */
    public synchronized Optional<Login> byId(String id, long now, Duration timeout, Duration maxAge) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT name, stamp, last_seen, created_at FROM logins WHERE id = ?")) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next() || now - row.getLong(3) > timeout.toMillis() || tooOld(row.getString(4), now, maxAge)) {
                    return Optional.empty();
                }
                return Optional.of(new Login(id, row.getString(1), row.getLong(2), row.getLong(3)));
            }
        }
    }

    /** 创建时间读不出来也算过期：这条记录不可信。 */
    private static boolean tooOld(String createdAt, long now, Duration maxAge) {
        try {
            return now - Instant.parse(createdAt).toEpochMilli() > maxAge.toMillis();
        } catch (DateTimeException unreadable) {
            return true;
        }
    }

    /** 最后访问时间。调用方最多每分钟调一次。 */
    public synchronized void touch(String id, long now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE logins SET last_seen = ? WHERE id = ?")) {
            update.setLong(1, now);
            update.setString(2, id);
            update.executeUpdate();
        }
    }

    public synchronized void delete(String id) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM logins WHERE id = ?")) {
            delete.setString(1, id);
            delete.executeUpdate();
        }
    }

    /** 这个用户的全部登录（管理员重置密码、停用时）。 */
    public synchronized int deleteAll(String name) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM logins WHERE name = ?")) {
            delete.setString(1, name);
            return delete.executeUpdate();
        }
    }

    /**
     * 这个用户除 {@code keepId} 以外的登录（自己改密码：别处失效、当前保留），返回删掉的登录 id，调用方按它们断开推送连接。
     * {@code keepId} 为 null 时全删。
     */
    public synchronized List<String> deleteOthers(String name, String keepId) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT id FROM logins WHERE name = ?")) {
            query.setString(1, name);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    if (!row.getString(1).equals(keepId)) {
                        ids.add(row.getString(1));
                    }
                }
            }
        }
        for (String id : ids) {
            delete(id);
        }
        return ids;
    }

    /** 自己改密码后当前这个登录保留：记上新的口令戳。 */
    public synchronized void restamp(String id, long stamp) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE logins SET stamp = ? WHERE id = ?")) {
            update.setLong(1, stamp);
            update.setString(2, id);
            update.executeUpdate();
        }
    }

    /** 清掉过期的（最后访问太久以前，或创建太久以前），返回删了几条。 */
    public synchronized int sweep(long now, Duration timeout, Duration maxAge) throws SQLException {
        int deleted;
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM logins WHERE last_seen < ?")) {
            delete.setLong(1, now - timeout.toMillis());
            deleted = delete.executeUpdate();
        }
        List<String> old = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT id, created_at FROM logins");
                ResultSet row = query.executeQuery()) {
            while (row.next()) {
                if (tooOld(row.getString(2), now, maxAge)) {
                    old.add(row.getString(1));
                }
            }
        }
        for (String id : old) {
            delete(id);
        }
        return deleted + old.size();
    }

    public synchronized long count(String name) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT count(*) FROM logins WHERE name = ?")) {
            query.setString(1, name);
            try (ResultSet row = query.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }
}
