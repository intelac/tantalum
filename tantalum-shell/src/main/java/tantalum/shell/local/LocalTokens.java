package tantalum.shell.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import tantalum.shell.auth.UserStore;
import tantalum.value.id.Principal;

/**
 * 连接这台电脑用的令牌（local-workspace L2、L3）。存在 {@code users.db} 的 {@code local_tokens} 表里，只存 SHA-256，
 * 原始令牌只在 {@link #issue} 的返回值里出现一次。有效期只认四种；到期或撤销后不能再用。到期超过 7 天的行在启动时删掉。
 * 令牌不进日志：这里不写日志，报错也不带令牌。
 */
public final class LocalTokens implements AutoCloseable {

    /** 插件页能选的有效期：半小时、1 小时、4 小时、1 天（Yi 2026-11-06 定）。 */
    public static final Set<Duration> VALIDITIES = Set.of(Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(4), Duration.ofDays(1));

    /** 令牌的前缀，连接程序据此认出“像令牌的参数”并拒绝（L2）。 */
    public static final String PREFIX = "tc_";

    /** 到期多久之后删掉。留几天是为了让旧令牌重连时报“已到期”而不是“无效”。 */
    private static final Duration KEEP_EXPIRED = Duration.ofDays(7);
    private static final int RANDOM_BYTES = 32;
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    /** 新发的令牌和到期时间。 */
    public record Issued(String token, Instant expiresAt) {

        @Override
        public String toString() {
            return "Issued[expiresAt=" + expiresAt + "]";
        }
    }

    public enum Status {
        VALID,
        /** 不是我们发的：关闭码 4401。 */
        INVALID,
        /** 到期：关闭码 4403。 */
        EXPIRED,
        /** 已在网页上撤销：关闭码 4403。 */
        REVOKED
    }

    /** {@link #verify} 的结果。{@code INVALID} 时用户和到期时间为 null，其余情况都有，日志能记是谁的令牌。 */
    public record Verdict(Status status, Principal principal, Instant expiresAt) {

        public Verdict {
            Objects.requireNonNull(status, "status");
        }

        public boolean valid() {
            return status == Status.VALID;
        }
    }

    private static final Verdict INVALID = new Verdict(Status.INVALID, null, null);

    private final Connection connection;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    private LocalTokens(Connection connection, Clock clock) {
        this.connection = connection;
        this.clock = clock;
    }

    /** 和用户表同一个文件，照 {@code PluginSwitches} 的做法打开；打开时删掉到期超过 7 天的行。 */
    public static LocalTokens open(Path dataDir, Clock clock) throws IOException, SQLException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve(UserStore.FILE);
        if (!Files.exists(file)) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS local_tokens (
                      hash       TEXT PRIMARY KEY,
                      principal  TEXT NOT NULL,
                      created_at INTEGER NOT NULL,
                      expires_at INTEGER NOT NULL,
                      revoked_at INTEGER
                    )""");
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
        LocalTokens tokens = new LocalTokens(connection, clock);
        tokens.purge();
        return tokens;
    }

    /** 发一个新令牌。有效期不是四种之一时抛 {@link IllegalArgumentException}。 */
    public synchronized Issued issue(Principal principal, Duration validity) {
        if (!VALIDITIES.contains(validity)) {
            throw new IllegalArgumentException("validity must be 30 minutes, 1 hour, 4 hours or 1 day: " + validity);
        }
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        String token = PREFIX + BASE64URL.encodeToString(bytes);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(validity);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO local_tokens (hash, principal, created_at, expires_at) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, hash(token));
            insert.setString(2, principal.value());
            insert.setLong(3, now.toEpochMilli());
            insert.setLong(4, expiresAt.toEpochMilli());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot save a local token for " + principal.value(), failure);
        }
        return new Issued(token, expiresAt);
    }

    /** 查令牌。撤销优先于到期：用户撤销过就告诉连接程序“已在网页上撤销”。 */
    public synchronized Verdict verify(String token) {
        if (token == null || !token.startsWith(PREFIX)) {
            return INVALID;
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT principal, expires_at, revoked_at FROM local_tokens WHERE hash = ?")) {
            query.setString(1, hash(token));
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    return INVALID;
                }
                Principal principal = new Principal(row.getString(1));
                Instant expiresAt = Instant.ofEpochMilli(row.getLong(2));
                row.getLong(3);
                boolean revoked = !row.wasNull();
                Status status = revoked ? Status.REVOKED : clock.instant().isBefore(expiresAt) ? Status.VALID : Status.EXPIRED;
                return new Verdict(status, principal, expiresAt);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot read local tokens", failure);
        }
    }

    /** 撤销这个用户还没撤销的全部令牌，返回撤销了几个。 */
    public synchronized int revokeAll(Principal principal) {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE local_tokens SET revoked_at = ? WHERE principal = ? AND revoked_at IS NULL")) {
            update.setLong(1, clock.instant().toEpochMilli());
            update.setString(2, principal.value());
            return update.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot revoke local tokens for " + principal.value(), failure);
        }
    }

    private synchronized void purge() throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM local_tokens WHERE expires_at < ?")) {
            delete.setLong(1, clock.instant().minus(KEEP_EXPIRED).toEpochMilli());
            delete.executeUpdate();
        }
    }

    /** SHA-256 的 base64url。令牌本身有 256 位随机数，不需要加盐或慢哈希。 */
    private static String hash(String token) {
        try {
            return BASE64URL.encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
