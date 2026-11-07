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

            Optional<Logins.Login> found = logins.check(issued.token(), T0 + 1000, TIMEOUT);
            assertEquals(new Logins.Login(issued.id(), "alice", 3, T0), found.orElseThrow());
            assertTrue(logins.check("not-a-token", T0, TIMEOUT).isEmpty());
            assertTrue(logins.check(issued.token(), T0 + TIMEOUT.toMillis() + 1, TIMEOUT).isEmpty(), "expired");

            logins.touch(issued.id(), T0 + TIMEOUT.toMillis());
            assertTrue(logins.check(issued.token(), T0 + TIMEOUT.toMillis() + 1, TIMEOUT).isPresent(), "touched: alive again");
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
            assertTrue(logins.check(laptop.token(), T0, TIMEOUT).isEmpty());
            assertTrue(logins.check(phone.token(), T0, TIMEOUT).isPresent());

            logins.restamp(phone.id(), 2);
            assertEquals(2, logins.check(phone.token(), T0, TIMEOUT).orElseThrow().stamp());

            assertEquals(1, logins.deleteAll("alice"));
            assertTrue(logins.check(phone.token(), T0, TIMEOUT).isEmpty());
            assertTrue(logins.check(bob.token(), T0, TIMEOUT).isPresent(), "another user's login stays");

            logins.touch(bob.id(), T0);
            assertEquals(0, logins.sweep(T0 + TIMEOUT.toMillis(), TIMEOUT), "not yet expired");
            assertEquals(1, logins.sweep(T0 + TIMEOUT.toMillis() + 1, TIMEOUT));
            assertFalse(logins.check(bob.token(), T0, TIMEOUT).isPresent());
        }
    }

    @Test
    void loginsSurviveReopen() throws Exception {
        Logins.Issued issued;
        try (UserStore store = UserStore.open(dir)) {
            issued = store.logins().create("alice", 0, T0);
        }
        try (UserStore store = UserStore.open(dir)) {
            assertTrue(store.logins().check(issued.token(), T0 + 1, TIMEOUT).isPresent());
        }
    }
}
