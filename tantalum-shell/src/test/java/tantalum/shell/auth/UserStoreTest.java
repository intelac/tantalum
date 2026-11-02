package tantalum.shell.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

}
