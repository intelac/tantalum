package tantalum.shell.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.value.id.Principal;

/** workspaces 任务 6（W2、需求 1.3–1.7）：登记、改名、删除；路径和名字在同一个人里不重复；别人看不到。 */
class ProjectsTest {

    private static final Principal YI = new Principal("yi");
    private static final Principal BOB = new Principal("bob");

    private static Clock at(long second) {
        return Clock.fixed(Instant.ofEpochSecond(second), ZoneOffset.UTC);
    }

    private static Projects.Problem refused(ThrowingCall call) throws Exception {
        return assertThrows(Projects.Refused.class, call::run).problem();
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }

    @Test
    void theNameDefaultsToTheLastSegmentAndTheNewestComesFirst() throws Exception {
        try (Projects projects = Projects.inMemory(Clock.systemUTC())) {
            Project app = projects.add(YI, "  C:\\APPL\\QTR_Quattro_github_3\\ ", null);
            assertEquals("QTR_Quattro_github_3", app.title());
            assertEquals("C:\\APPL\\QTR_Quattro_github_3\\", app.path());
            assertTrue(app.id().matches("p-[a-z0-9]{8}"), app.id());
            Thread.sleep(2);
            Project wiki = projects.add(YI, "C:\\quattro-run\\wiki", "知识库");
            assertEquals(List.of(wiki, app), projects.list(YI));
        }
    }

    @Test
    void theSamePathOrNameIsRefusedForTheSamePerson() throws Exception {
        try (Projects projects = Projects.inMemory(Clock.systemUTC())) {
            projects.add(YI, "C:\\APPL\\Quattro", null);
            assertEquals(Projects.Problem.PATH_TAKEN, refused(() -> projects.add(YI, "c:/appl/quattro/", "other")));
            assertEquals(Projects.Problem.TITLE_TAKEN, refused(() -> projects.add(YI, "C:\\elsewhere\\Quattro", null)));
            projects.add(BOB, "C:\\APPL\\Quattro", null);
            assertEquals(1, projects.list(BOB).size(), "another person may register the same directory");
        }
    }

    @Test
    void pathsAndNamesAreChecked() throws Exception {
        try (Projects projects = Projects.inMemory(Clock.systemUTC())) {
            assertEquals(Projects.Problem.PATH_INVALID, refused(() -> projects.add(YI, "app", null)));
            assertEquals(Projects.Problem.PATH_INVALID, refused(() -> projects.add(YI, null, null)));
            assertEquals(Projects.Problem.TITLE_INVALID, refused(() -> projects.add(YI, "/a", "x".repeat(81))));
            assertEquals("x".repeat(80), projects.add(YI, "/a", " " + "x".repeat(80) + " ").title());
        }
    }

    @Test
    void renamingKeepsThePathAndChecksTheName() throws Exception {
        try (Projects projects = Projects.inMemory(Clock.systemUTC())) {
            Project app = projects.add(YI, "/home/yi/app", null);
            projects.add(YI, "/home/yi/wiki", null);
            assertEquals(Projects.Problem.TITLE_TAKEN, refused(() -> projects.rename(YI, app.id(), "wiki")));
            assertEquals(Projects.Problem.TITLE_INVALID, refused(() -> projects.rename(YI, app.id(), "  ")));
            assertEquals(Projects.Problem.NOT_FOUND, refused(() -> projects.rename(BOB, app.id(), "mine")));
            Project renamed = projects.rename(YI, app.id(), " 应用 ");
            assertEquals("应用", renamed.title());
            assertEquals("/home/yi/app", renamed.path());
            assertEquals(renamed, projects.find(YI, app.id()).orElseThrow());
            assertEquals(renamed, projects.rename(YI, app.id(), "应用"), "keeping its own name is not a clash");
        }
    }

    @Test
    void deletingRemovesOnlyTheOwnersEntry() throws Exception {
        try (Projects projects = Projects.inMemory(Clock.systemUTC())) {
            Project app = projects.add(YI, "/home/yi/app", null);
            assertFalse(projects.delete(BOB, app.id()));
            assertTrue(projects.find(BOB, app.id()).isEmpty());
            assertTrue(projects.delete(YI, app.id()));
            assertTrue(projects.list(YI).isEmpty());
            assertFalse(projects.delete(YI, app.id()));
        }
    }

    @Test
    void entriesSurviveReopening(@TempDir Path dataDir) throws Exception {
        Project app;
        try (Projects projects = Projects.open(dataDir, at(100))) {
            app = projects.add(YI, "/home/yi/app", null);
        }
        try (Projects projects = Projects.open(dataDir, at(200))) {
            assertEquals(List.of(app), projects.list(YI));
        }
    }
}
