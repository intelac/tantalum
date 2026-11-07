package tantalum.shell.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.PrivateFiles;

class UserStoreTest {

    @TempDir
    Path dir;

    @Test
    void correctPasswordVerifiesAndWrongOneDoesNot() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            store.add("alice", "correct horse");
            assertTrue(store.verify("alice", "correct horse"));
            assertFalse(store.verify("alice", "wrong"));
            assertFalse(store.verify("nobody", "correct horse"));
        }
    }

    @Test
    void passwordIsStoredOnlyAsAnArgon2Hash() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            store.add("alice", "correct horse");
            assertTrue(store.verify("alice", "correct horse"));
        }
        String file = new String(Files.readAllBytes(dir.resolve(UserStore.FILE)), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(file.contains("$argon2id$"));
        assertFalse(file.contains("correct horse"));
    }

    @Test
    void usersSurviveReopen() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            store.add("alice", "pw");
        }
        try (UserStore store = UserStore.open(dir)) {
            assertEquals(1, store.count());
            assertTrue(store.verify("alice", "pw"));
        }
    }

    @Test
    void fileIsOnlyAccessibleToTheOwner() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            store.add("alice", "pw");
        }
        assumeTrue(PrivateFiles.posix(dir), "Windows 上权限靠继承的 ACL，见 WindowsFilesTest");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(UserStore.FILE))));
    }

    @Test
    void unsafeNamesAreRejected() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            for (String name : new String[] {"..", "a/b", "Alice", "a b", ""}) {
                assertThrows(IllegalArgumentException.class, () -> store.add(name, "pw"), name);
            }
            assertEquals(0, store.count());
        }
    }

    /** windows-server 需求 3.1：Windows 设备名不能注册，所有平台一样，消息写明原因；只是像的名字照常。 */
    @Test
    void windowsDeviceNamesCannotBeUserNames() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            for (String name : new String[] {"con", "nul", "aux", "prn", "com1", "lpt9"}) {
                UserStore.Refused refused = assertThrows(UserStore.Refused.class, () -> store.add(name, "pw"), name);
                assertEquals(UserStore.Refused.Kind.RESERVED_NAME, refused.kind());
                assertEquals(name + " is reserved by Windows and cannot be a user name.", refused.getMessage());
            }
            store.add("console", "pw");
            assertEquals(1, store.count());
        }
    }

    @Test
    void duplicateUserIsRejected() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            store.add("alice", "pw");
            assertThrows(IllegalArgumentException.class, () -> store.add("alice", "other"));
        }
    }

    @Test
    void disabledUserCannotLogIn() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            store.add("alice", "pw");
            store.disable("alice");
            assertFalse(store.verify("alice", "pw"));
            assertTrue(store.status("alice").orElseThrow().disabled());
        }
    }

    @Test
    void resetChangesThePassword() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            store.add("alice", "old");
            store.resetPassword("alice", "new");
            assertFalse(store.verify("alice", "old"));
            assertTrue(store.verify("alice", "new"));
        }
    }

    @Test
    void changingAnUnknownUserFails() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            assertThrows(IllegalArgumentException.class, () -> store.resetPassword("nobody", "x"));
            assertThrows(IllegalArgumentException.class, () -> store.disable("nobody"));
        }
    }

    /** 任务 38：改密码让口令戳变化，旧的登录据此失效；停用也在状态里。 */
    @Test
    void resettingThePasswordChangesTheStamp() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            store.add("alice", "old");
            long before = store.status("alice").orElseThrow().stamp();
            store.resetPassword("alice", "new");
            assertTrue(store.status("alice").orElseThrow().stamp() > before);
            store.disable("alice");
            assertTrue(store.status("alice").orElseThrow().disabled());
            assertTrue(store.status("nobody").isEmpty());
        }
    }

    /** ops-hardening-2 需求 2.1：版本 1 的库（没有 admin 列）打开时升级，最早的账号成为管理员。 */
    @Test
    void aVersion1StoreGetsTheAdminColumnAndItsEarliestAccountBecomesAdmin() throws Exception {
        java.nio.file.Files.createDirectories(dir);
        try (java.sql.Connection raw = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(UserStore.FILE));
                java.sql.Statement statement = raw.createStatement()) {
            statement.execute("""
                    CREATE TABLE users (name TEXT PRIMARY KEY, password_hash TEXT NOT NULL, disabled INTEGER NOT NULL DEFAULT 0,
                      created_at TEXT NOT NULL, password_stamp INTEGER NOT NULL DEFAULT 0)""");
            statement.execute("INSERT INTO users (name, password_hash, created_at) VALUES ('later', 'x', '2026-11-02T00:00:00Z')");
            statement.execute("INSERT INTO users (name, password_hash, created_at) VALUES ('first', 'x', '2026-11-01T00:00:00Z')");
            statement.execute("PRAGMA user_version=1");
        }
        try (UserStore store = UserStore.open(dir)) {
            assertEquals(java.util.List.of(true, false), store.list().stream().map(UserStore.Account::admin).toList());
            assertEquals("first", store.list().getFirst().name());
            assertTrue(store.status("first").orElseThrow().admin());
            assertTrue(store.logins().check("x", 0, java.time.Duration.ofHours(1)).isEmpty(), "logins table exists");
        }
        // 再打开一次：幂等，不再改管理员
        try (UserStore store = UserStore.open(dir)) {
            store.setAdmin("later", true);
            store.setAdmin("first", false);
        }
        try (UserStore store = UserStore.open(dir)) {
            assertFalse(store.status("first").orElseThrow().admin());
        }
    }

    /** 需求 2.2、2.4：列表、启用、设管理员；最后一个启用的管理员不能被停用或取消。 */
    @Test
    void adminsListEnableAndTheLastAdminIsProtected() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            store.add("root", "pw", true);
            store.add("alice", "pw");
            assertEquals(java.util.List.of("root", "alice"), store.list().stream().map(UserStore.Account::name).toList());
            assertTrue(store.list().getFirst().admin());
            assertFalse(store.list().get(1).admin());

            UserStore.Refused last = assertThrows(UserStore.Refused.class, () -> store.setAdmin("root", false));
            assertEquals(UserStore.Refused.Kind.LAST_ADMIN, last.kind());
            assertEquals(UserStore.Refused.Kind.LAST_ADMIN, assertThrows(UserStore.Refused.class, () -> store.disable("root")).kind());

            store.setAdmin("alice", true);
            store.setAdmin("root", false);
            assertFalse(store.status("root").orElseThrow().admin());
            store.disable("root");
            assertTrue(store.status("root").orElseThrow().disabled());
            assertFalse(store.verify("root", "pw"));
            store.enable("root");
            assertTrue(store.verify("root", "pw"));
            // alice 现在是唯一启用的管理员：停用她也不行
            assertEquals(UserStore.Refused.Kind.LAST_ADMIN, assertThrows(UserStore.Refused.class, () -> store.disable("alice")).kind());
            assertEquals(UserStore.Refused.Kind.NO_SUCH, assertThrows(UserStore.Refused.class, () -> store.enable("nobody")).kind());
        }
    }
}
