package tantalum.shell.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/** workspaces 任务 7（W8、需求 6）：归档、取消归档、列出；只看本人。 */
class ArchiveTest {

    private static final Principal YI = new Principal("yi");
    private static final Principal BOB = new Principal("bob");
    private static final SessionId ONE = new SessionId("s-1");
    private static final SessionId TWO = new SessionId("s-2");

    @Test
    void archivingAndRestoringArePerPerson() throws Exception {
        try (Archive archive = Archive.inMemory(Clock.systemUTC())) {
            archive.set(YI, ONE, true);
            archive.set(YI, ONE, true);
            archive.set(YI, TWO, true);
            assertTrue(archive.archived(YI, ONE));
            assertFalse(archive.archived(BOB, ONE));
            assertEquals(Set.of(ONE, TWO), archive.all(YI));
            archive.set(YI, ONE, false);
            archive.set(YI, ONE, false);
            assertEquals(Set.of(TWO), archive.all(YI));
            assertEquals(Set.of(), archive.all(BOB));
        }
    }

    @Test
    void itSurvivesReopening(@TempDir Path dataDir) throws Exception {
        try (Archive archive = Archive.open(dataDir, Clock.systemUTC())) {
            archive.set(YI, ONE, true);
        }
        try (Archive archive = Archive.open(dataDir, Clock.systemUTC())) {
            assertTrue(archive.archived(YI, ONE));
        }
    }
}
