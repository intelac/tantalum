package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

class SessionPathsTest {

    private static final List<String> HOSTILE = List.of(
            "../x", "..", ".", "a/b", "a\\b", "/etc/passwd", "../../../../etc/passwd", "a\0b", "\0",
            "%2e%2e", "%2F", "x".repeat(5000), "../".repeat(400), "用户名", "😀", "é", "é", " a ", "a.jsonl");

    @TempDir
    Path dir;

    @Test
    void hostileNamesStayInsideTheirDirectoryAndCanBeCreated() throws Exception {
        SessionPaths paths = new SessionPaths(dir);
        for (String hostile : HOSTILE) {
            Path file = paths.sessionFile(new Principal(hostile), new SessionId(hostile));
            assertTrue(file.startsWith(paths.sessionsDir()), hostile);
            assertEquals(paths.sessionsDir(), file.getParent().getParent(), hostile);
            assertTrue(file.getFileName().toString().endsWith(".jsonl"), hostile);
            Files.createDirectories(file.getParent());
            Files.createFile(file);
        }
    }

    @Test
    void distinctIdsNeverShareAName() {
        Set<String> names = new HashSet<>();
        List<String> ids = new java.util.ArrayList<>(HOSTILE);
        ids.addAll(List.of("a", "A", "%41", "a b", "a+b", "a%20b", "x".repeat(5001), "x".repeat(120), "x".repeat(121), "s-1", "s_1"));
        for (String id : ids) {
            assertTrue(names.add(SessionPaths.encode(id).toLowerCase(Locale.ROOT)), id);
        }
    }

    @Test
    void namesDifferingOnlyInCaseDoNotCollideOnCaseInsensitiveFilesystems() {
        assertNotEquals(SessionPaths.encode("Alice").toLowerCase(Locale.ROOT), SessionPaths.encode("alice").toLowerCase(Locale.ROOT));
    }

    @Test
    void longIdsDifferingOnlyAfterTheCutGetDifferentNames() {
        String prefix = "x".repeat(300);
        assertNotEquals(SessionPaths.encode(prefix + "a"), SessionPaths.encode(prefix + "b"));
    }

    @Test
    void namesAreBounded() {
        for (String hostile : HOSTILE) {
            assertTrue(SessionPaths.encode(hostile).length() <= SessionPaths.MAX_NAME_BYTES, hostile);
        }
        assertEquals(SessionPaths.MAX_NAME_BYTES, SessionPaths.encode("%".repeat(100)).length());
    }

    @Test
    void ordinaryConsoleIdKeepsItsName() {
        assertEquals("s-1a2b_c", SessionPaths.encode("s-1a2b_c"));
        assertFalse(SessionPaths.encode("..").contains("."));
    }

    @Test
    void sameIdAlwaysMapsToSamePath() {
        SessionPaths paths = new SessionPaths(dir);
        assertEquals(paths.sessionFile(new Principal("u"), new SessionId("s")), paths.sessionFile(new Principal("u"), new SessionId("s")));
    }
}
