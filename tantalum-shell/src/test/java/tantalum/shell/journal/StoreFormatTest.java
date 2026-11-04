package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreFormatTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("format.json");
    }

    @Test
    void newDirectoryGetsCurrentVersions() throws Exception {
        assertEquals(new StoreFormat(StoreFormat.STORAGE), StoreFormat.check(dir));
        assertEquals("{\"storage\":2}\n", Files.readString(file()));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file())));
        assertEquals(1, Files.list(dir).count());
    }

    @Test
    void currentVersionsPass() throws Exception {
        StoreFormat.check(dir);
        byte[] before = Files.readAllBytes(file());
        StoreFormat.check(dir);
        assertEquals(new String(before), Files.readString(file()));
    }

    @Test
    void newerStorageIsRefusedAndNothingChanges() throws Exception {
        String future = "{\"storage\":99,\"semantics\":1}\n";
        Files.writeString(file(), future);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> StoreFormat.check(dir));
        assertTrue(failure.getMessage().contains("99"), failure.getMessage());
        assertEquals(future, Files.readString(file()));
        assertEquals(1, Files.list(dir).count());
    }

    /** 以前的 {@code semantics} 字段读到也忽略，文件不改。 */
    @Test
    void anOldSemanticsFieldIsIgnored() throws Exception {
        Files.writeString(file(), "{\"storage\":2,\"semantics\":0}\n");
        assertEquals(StoreFormat.STORAGE, StoreFormat.check(dir).storage());
        assertEquals("{\"storage\":2,\"semantics\":0}\n", Files.readString(file()));
    }

    /** record-results 需求 8.1：旧格式的会话文件不迁移，拒绝启动，什么都不改。 */
    @Test
    void storageOneWithSessionFilesIsRefusedAndNothingChanges() throws Exception {
        String old = "{\"storage\":1,\"semantics\":2}\n";
        Files.writeString(file(), old);
        Path session = dir.resolve("sessions/yi/s-1.jsonl");
        Files.createDirectories(session.getParent());
        Files.writeString(session, "{\"format\":1}\n");
        Files.writeString(dir.resolve("users.db"), "users");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> StoreFormat.check(dir));

        assertTrue(failure.getMessage().contains("sessions") && failure.getMessage().contains("index.db"), failure.getMessage());
        assertEquals(old, Files.readString(file()));
        assertEquals("{\"format\":1}\n", Files.readString(session));
    }

    /** 需求 8.2：没有会话文件时直接升级，用户库不动。 */
    @Test
    void storageOneWithoutSessionFilesIsUpgraded() throws Exception {
        Files.writeString(file(), "{\"storage\":1,\"semantics\":2}\n");
        Files.createDirectories(dir.resolve("sessions/yi"));
        Files.writeString(dir.resolve("users.db"), "users");

        assertEquals(StoreFormat.STORAGE, StoreFormat.check(dir).storage());

        assertEquals("{\"storage\":2}\n", Files.readString(file()));
        assertEquals("users", Files.readString(dir.resolve("users.db")));
    }

    @Test
    void oldJournalWithoutFormatIsRefusedAndNothingChanges() throws Exception {
        Files.writeString(dir.resolve("journal.db"), "old data");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> StoreFormat.check(dir));
        assertTrue(failure.getMessage().contains("journal.db"), failure.getMessage());
        assertFalse(Files.exists(file()));
        assertEquals("old data", Files.readString(dir.resolve("journal.db")));
    }

    @Test
    void invalidFormatFileIsRefused() throws Exception {
        Files.writeString(file(), "not json");
        assertThrows(IllegalStateException.class, () -> StoreFormat.check(dir));
        assertEquals("not json", Files.readString(file()));
    }
}
