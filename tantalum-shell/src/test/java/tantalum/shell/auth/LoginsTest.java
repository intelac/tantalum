package tantalum.shell.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ops-hardening-2 需求 1：登录记录在 users.db 里，只存令牌的哈希，按最后访问时间过期。 */
class LoginsTest {

    private static final Duration TIMEOUT = Duration.ofHours(12);
    private static final Duration MAX_AGE = Duration.ofDays(30);
    private static final long T0 = 1_700_000_000_000L;

    @TempDir
    Path dir;

    @Test
    void aLoginIsFoundByItsTokenUntilItTimesOut() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            Logins logins = store.logins();
            Logins.Issued issued = logins.create("alice", 3, T0);
            assertEquals(43, issued.token().length(), "32 random bytes, base64url");
            assertEquals(Logins.hash(issued.token()), issued.id());

            Optional<Logins.Login> found = logins.check(issued.token(), T0 + 1000, TIMEOUT, MAX_AGE);
            assertEquals(new Logins.Login(issued.id(), "alice", 3, T0), found.orElseThrow());
            assertTrue(logins.check("not-a-token", T0, TIMEOUT, MAX_AGE).isEmpty());
            assertTrue(logins.check(issued.token(), T0 + TIMEOUT.toMillis() + 1, TIMEOUT, MAX_AGE).isEmpty(), "expired");

            logins.touch(issued.id(), T0 + TIMEOUT.toMillis());
            assertTrue(logins.check(issued.token(), T0 + TIMEOUT.toMillis() + 1, TIMEOUT, MAX_AGE).isPresent(), "touched: alive again");
        }
    }

    @Test
    void onlyTheHashIsStoredAndEachLoginIsDifferent() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            Logins.Issued first = store.logins().create("alice", 0, T0);
            Logins.Issued second = store.logins().create("alice", 0, T0);
            assertNotEquals(first.token(), second.token());
            try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(UserStore.FILE));
                    Statement statement = raw.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT id FROM logins")) {
                while (rows.next()) {
                    String id = rows.getString(1);
                    assertNotEquals(first.token(), id);
                    assertNotEquals(second.token(), id);
                }
            }
            assertEquals(2, store.logins().count("alice"));
        }
    }

    @Test
    void deleteRestampAndSweep() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            Logins logins = store.logins();
            Logins.Issued laptop = logins.create("alice", 1, T0);
            Logins.Issued phone = logins.create("alice", 1, T0);
            Logins.Issued bob = logins.create("bob", 1, T0);

            logins.delete(laptop.id());
            assertTrue(logins.check(laptop.token(), T0, TIMEOUT, MAX_AGE).isEmpty());
            assertTrue(logins.check(phone.token(), T0, TIMEOUT, MAX_AGE).isPresent());

            logins.restamp(phone.id(), 2);
            assertEquals(2, logins.check(phone.token(), T0, TIMEOUT, MAX_AGE).orElseThrow().stamp());

            assertEquals(1, logins.deleteAll("alice"));
            assertTrue(logins.check(phone.token(), T0, TIMEOUT, MAX_AGE).isEmpty());
            assertTrue(logins.check(bob.token(), T0, TIMEOUT, MAX_AGE).isPresent(), "another user's login stays");

            logins.touch(bob.id(), T0);
            assertEquals(0, logins.sweep(T0 + TIMEOUT.toMillis(), TIMEOUT, MAX_AGE), "not yet expired");
            assertEquals(1, logins.sweep(T0 + TIMEOUT.toMillis() + 1, TIMEOUT, MAX_AGE));
            assertFalse(logins.check(bob.token(), T0, TIMEOUT, MAX_AGE).isPresent());
        }
    }

    @Test
    void loginsSurviveReopen() throws Exception {
        Logins.Issued issued;
        try (UserStore store = UserStore.open(dir)) {
            issued = store.logins().create("alice", 0, T0);
        }
        try (UserStore store = UserStore.open(dir)) {
            assertTrue(store.logins().check(issued.token(), T0 + 1, TIMEOUT, MAX_AGE).isPresent());
        }
    }

    /** review-fixes 需求 11.2：不管用得多勤，创建满 30 天的登录作废；sweep 也清它。 */
    @Test
    void aLoginEndsAfterItsMaximumLifetimeHoweverOftenItIsUsed() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            Logins logins = store.logins();
            Logins.Issued issued = logins.create("alice", 0, T0);
            long lastDay = T0 + MAX_AGE.toMillis();
            logins.touch(issued.id(), lastDay);
            assertTrue(logins.check(issued.token(), lastDay, TIMEOUT, MAX_AGE).isPresent(), "still within 30 days");
            assertTrue(logins.byId(issued.id(), lastDay + 1, TIMEOUT, MAX_AGE).isEmpty(), "30 days passed");
            assertEquals(0, logins.sweep(lastDay, TIMEOUT, MAX_AGE));
            assertEquals(1, logins.sweep(lastDay + 1, TIMEOUT, MAX_AGE));
            assertEquals(0, logins.count("alice"));
        }
    }

    /** 自己改密码时要断开的登录：删掉的 id 回给调用方；keepId 为 null 时全删。 */
    @Test
    void deleteOthersReturnsTheDeletedIds() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            Logins logins = store.logins();
            Logins.Issued laptop = logins.create("alice", 1, T0);
            Logins.Issued phone = logins.create("alice", 1, T0);
            Logins.Issued bob = logins.create("bob", 1, T0);
            assertEquals(java.util.List.of(phone.id()), logins.deleteOthers("alice", laptop.id()));
            assertTrue(logins.check(laptop.token(), T0, TIMEOUT, MAX_AGE).isPresent());
            assertEquals(java.util.List.of(laptop.id()), logins.deleteOthers("alice", null));
            assertEquals(1, logins.count("bob"));
            assertEquals(bob.id(), logins.check(bob.token(), T0, TIMEOUT, MAX_AGE).orElseThrow().id());
        }
    }
}
