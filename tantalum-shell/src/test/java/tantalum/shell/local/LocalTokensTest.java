package tantalum.shell.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.auth.UserStore;
import tantalum.shell.local.LocalTokens.Issued;
import tantalum.shell.local.LocalTokens.Status;
import tantalum.shell.local.LocalTokens.Verdict;
import tantalum.value.id.Principal;

/** local-workspace L2、L3、需求 1.1、1.7：有期限、可撤销、只存哈希的连接令牌。 */
class LocalTokensTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final Instant START = Instant.parse("2026-11-06T02:00:00Z");

    @TempDir
    Path data;

    private final Ticking clock = new Ticking(START);

    /** 插件页只给四种有效期，接口收到别的值也不能发出令牌。 */
    @Test
    void onlyTheFourValiditiesAreIssued() throws Exception {
        try (LocalTokens tokens = LocalTokens.open(data, clock)) {
            for (Duration validity : List.of(Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(4), Duration.ofDays(1))) {
                Issued issued = tokens.issue(ALICE, validity);
                assertEquals(START.plus(validity), issued.expiresAt());
                assertEquals(START.plus(validity), tokens.verify(issued.token()).expiresAt());
            }
            for (Duration other : List.of(Duration.ZERO, Duration.ofMinutes(-30), Duration.ofHours(2), Duration.ofDays(1).plusSeconds(1),
                    Duration.ofDays(30))) {
                assertThrows(IllegalArgumentException.class, () -> tokens.issue(ALICE, other), other.toString());
            }
        }
    }

    /** 需求 1.7：库里只有哈希，原始令牌和它的随机部分都查不到。 */
    @Test
    void theDatabaseHoldsOnlyTheHash() throws Exception {
        Issued issued;
        try (LocalTokens tokens = LocalTokens.open(data, clock)) {
            issued = tokens.issue(ALICE, Duration.ofHours(1));
        }
        assertTrue(issued.token().matches("tc_[A-Za-z0-9_-]{43}"), "32 random bytes as base64url");
        String random = issued.token().substring(3);
        List<String> cells = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + data.resolve(UserStore.FILE));
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT * FROM local_tokens")) {
            while (rows.next()) {
                for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                    cells.add(String.valueOf(rows.getObject(column)));
                }
            }
        }
        assertFalse(cells.isEmpty());
        for (String cell : cells) {
            assertFalse(cell.contains(random), "raw token stored: " + cell);
        }
        String sha256 = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(issued.token().getBytes(StandardCharsets.UTF_8)));
        assertTrue(cells.contains(sha256));
    }

    @Test
    void aTokenBelongsToItsUser() throws Exception {
        try (LocalTokens tokens = LocalTokens.open(data, clock)) {
            Issued alice = tokens.issue(ALICE, Duration.ofHours(1));
            Issued bob = tokens.issue(BOB, Duration.ofHours(4));
            assertEquals(new Verdict(Status.VALID, ALICE, START.plus(Duration.ofHours(1))), tokens.verify(alice.token()));
            assertEquals(ALICE, tokens.verify(alice.token()).principal());
            assertEquals(BOB, tokens.verify(bob.token()).principal());
            assertFalse(alice.token().equals(tokens.issue(ALICE, Duration.ofHours(1)).token()), "every token is new");
        }
    }

    /** 4401：不是我们发的、格式不对、空的。都不说是谁的。 */
    @Test
    void unknownTokensAreInvalid() throws Exception {
        try (LocalTokens tokens = LocalTokens.open(data, clock)) {
            String issued = tokens.issue(ALICE, Duration.ofHours(1)).token();
            // 改最后一个字符：一定要换成不同的字符（随机令牌有 1/64 的机会本来就以 A 结尾）
            char last = issued.charAt(issued.length() - 1);
            String changed = issued.substring(0, issued.length() - 1) + (last == 'A' ? 'B' : 'A');
            Map<String, String> bad = new LinkedHashMap<>();
            bad.put("null", null);
            bad.put("empty", "");
            bad.put("prefix only", "tc_");
            bad.put("nonsense", "nonsense");
            bad.put("one more character", issued + "x");
            bad.put("last character changed", changed);
            bad.put("without the prefix", issued.substring(3));
            // 断言消息只写是哪一种，不打印令牌
            bad.forEach((what, token) -> {
                Verdict verdict = tokens.verify(token);
                assertEquals(Status.INVALID, verdict.status(), what);
                assertEquals(null, verdict.principal(), what);
            });
        }
    }

    /** L3：到期那一刻起不能再用。 */
    @Test
    void anExpiredTokenIsRefused() throws Exception {
        try (LocalTokens tokens = LocalTokens.open(data, clock)) {
            String token = tokens.issue(ALICE, Duration.ofMinutes(30)).token();
            clock.now = START.plus(Duration.ofMinutes(30)).minusMillis(1);
            assertEquals(Status.VALID, tokens.verify(token).status());
            clock.now = START.plus(Duration.ofMinutes(30));
            assertEquals(Status.EXPIRED, tokens.verify(token).status());
            assertEquals(ALICE, tokens.verify(token).principal(), "the endpoint logs whose token it was");
        }
    }

    /** L3：撤销这个用户的全部令牌，别人的不受影响；撤销后的令牌永远不能再用。 */
    @Test
    void revokingStopsEveryTokenOfThatUser() throws Exception {
        try (LocalTokens tokens = LocalTokens.open(data, clock)) {
            String first = tokens.issue(ALICE, Duration.ofHours(1)).token();
            String second = tokens.issue(ALICE, Duration.ofDays(1)).token();
            String bob = tokens.issue(BOB, Duration.ofHours(1)).token();
            assertEquals(2, tokens.revokeAll(ALICE));
            assertEquals(Status.REVOKED, tokens.verify(first).status());
            assertEquals(Status.REVOKED, tokens.verify(second).status());
            assertEquals(Status.VALID, tokens.verify(bob).status());
            assertEquals(0, tokens.revokeAll(ALICE), "already revoked");
            String fresh = tokens.issue(ALICE, Duration.ofHours(1)).token();
            assertEquals(Status.VALID, tokens.verify(fresh).status(), "a new token after revoking works");
            assertEquals(Status.REVOKED, tokens.verify(first).status());
        }
    }

    /** 到期超过 7 天的行在启动时删掉；7 天内的留着，仍报“已到期”而不是“无效”。 */
    @Test
    void oldRowsArePurgedAtStartup() throws Exception {
        String old;
        String recent;
        try (LocalTokens tokens = LocalTokens.open(data, clock)) {
            old = tokens.issue(ALICE, Duration.ofMinutes(30)).token();
            recent = tokens.issue(ALICE, Duration.ofDays(1)).token();
        }
        clock.now = START.plus(Duration.ofMinutes(30)).plus(Duration.ofDays(7)).plusMillis(1);
        try (LocalTokens tokens = LocalTokens.open(data, clock)) {
            assertEquals(Status.INVALID, tokens.verify(old).status());
            assertEquals(Status.EXPIRED, tokens.verify(recent).status());
        }
        assertEquals(1, rowCount());
    }

    /** 和用户表同一个文件，重启后还在，权限只给服务自己。 */
    @Test
    void tokensLiveInTheUsersDatabase() throws Exception {
        String token;
        try (LocalTokens tokens = LocalTokens.open(data, clock)) {
            token = tokens.issue(ALICE, Duration.ofHours(4)).token();
        }
        try (UserStore users = UserStore.open(data); LocalTokens tokens = LocalTokens.open(data, clock)) {
            users.add("alice", "a-long-password");
            assertEquals(Status.VALID, tokens.verify(token).status());
        }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(data.resolve(UserStore.FILE))));
    }

    private int rowCount() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + data.resolve(UserStore.FILE));
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM local_tokens")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** 手动拨的时钟。 */
    static final class Ticking extends Clock {

        volatile Instant now;

        Ticking(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
