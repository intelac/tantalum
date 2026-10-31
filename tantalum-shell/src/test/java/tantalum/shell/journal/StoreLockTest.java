package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreLockTest {

    @TempDir
    Path dir;

    @Test
    void secondAcquireFailsWhileHeld() throws Exception {
        StoreLock held = StoreLock.acquire(dir);
        assertThrows(IllegalStateException.class, () -> StoreLock.acquire(dir));
        held.close();
    }

    @Test
    void lockIsAvailableAgainAfterRelease() throws Exception {
        StoreLock.acquire(dir).close();
        assertDoesNotThrow(() -> StoreLock.acquire(dir).close());
    }
}
