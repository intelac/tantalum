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

/** workspaces 任务 7（W8、需求 6）：归档、取消归档、列出；只看本人。session-find-pin R1：置顶，和归档互斥。 */
class SessionMarksTest {

    private static final Principal YI = new Principal("yi");
    private static final Principal BOB = new Principal("bob");
    private static final SessionId ONE = new SessionId("s-1");
    private static final SessionId TWO = new SessionId("s-2");

    @Test
    void archivingAndRestoringArePerPerson() throws Exception {
        try (SessionMarks archive = SessionMarks.inMemory(Clock.systemUTC())) {
            archive.setArchived(YI, ONE, true);
            archive.setArchived(YI, ONE, true);
            archive.setArchived(YI, TWO, true);
            assertTrue(archive.archived(YI, ONE));
            assertFalse(archive.archived(BOB, ONE));
            assertEquals(Set.of(ONE, TWO), archive.allArchived(YI));
            archive.setArchived(YI, ONE, false);
            archive.setArchived(YI, ONE, false);
            assertEquals(Set.of(TWO), archive.allArchived(YI));
            assertEquals(Set.of(), archive.allArchived(BOB));
        }
    }

    @Test
    void itSurvivesReopening(@TempDir Path dataDir) throws Exception {
        try (SessionMarks archive = SessionMarks.open(dataDir, Clock.systemUTC())) {
            archive.setArchived(YI, ONE, true);
        }
        try (SessionMarks archive = SessionMarks.open(dataDir, Clock.systemUTC())) {
            assertTrue(archive.archived(YI, ONE));
        }
    }

    @Test
    void archivingUnpinsAndRestoringDoesNotPinAgain() throws Exception {
        try (SessionMarks marks = SessionMarks.inMemory(Clock.systemUTC())) {
            marks.setPinned(YI, ONE, true);
            marks.setPinned(YI, ONE, true);
            marks.setPinned(YI, TWO, true);
            assertEquals(Set.of(ONE, TWO), marks.allPinned(YI));
            assertEquals(Set.of(), marks.allPinned(BOB));
            marks.setArchived(YI, ONE, true);
            assertEquals(Set.of(TWO), marks.allPinned(YI));
            marks.setArchived(YI, ONE, false);
            assertEquals(Set.of(TWO), marks.allPinned(YI));
            marks.setPinned(YI, TWO, false);
            assertEquals(Set.of(), marks.allPinned(YI));
        }
    }
}
