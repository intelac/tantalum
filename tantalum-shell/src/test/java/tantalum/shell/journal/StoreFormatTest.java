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
        assertEquals(new StoreFormat(StoreFormat.STORAGE, StoreFormat.SEMANTICS), StoreFormat.check(dir));
        assertEquals("{\"storage\":1,\"semantics\":" + StoreFormat.SEMANTICS + "}\n", Files.readString(file()));
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

    @Test
    void semanticsChangeStartsAndIsRecorded() throws Exception {
        Files.writeString(file(), "{\"storage\":1,\"semantics\":0}\n");
        assertEquals(StoreFormat.SEMANTICS, StoreFormat.check(dir).semantics());
        assertEquals("{\"storage\":1,\"semantics\":" + StoreFormat.SEMANTICS + "}\n", Files.readString(file()));
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
