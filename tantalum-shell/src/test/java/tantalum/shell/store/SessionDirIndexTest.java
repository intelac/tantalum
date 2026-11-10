package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.model.TokenUsage;

/** workspaces 任务 8（需求 2.8、5.2、5.3）：索引记下工作目录和改的名字，改名不算活动，删掉索引重建后一样。 */
class SessionDirIndexTest {

    private static final Principal YI = new Principal("yi");
    private static final SessionId S1 = new SessionId("s1");
    private static final SessionId S2 = new SessionId("s2");
    private static final Instant T1 = Instant.parse("2026-11-10T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-10T11:00:00Z");

    @TempDir
    Path dir;

    private List<SessionIndex.Session> scenario(SessionIndex index, String... renames) throws Exception {
        IndexFixture fixture;
        try (IndexWriter writer = new IndexWriter(index::write, 1, Duration.ofHours(1), 1000)) {
            fixture = new IndexFixture(new SessionPaths(dir), writer::onDurable, writer::onStep);
            fixture.reply(fixture.prompt(YI, S1, T1, "review the uncommitted code", "C:\\APPL\\Quattro"), T1, new TokenUsage(1, 1, 0, 0));
            fixture.reply(fixture.prompt(YI, S2, T1, "plain chat"), T1, new TokenUsage(1, 1, 0, 0));
            for (String title : renames) {
                fixture.rename(YI, S1, T2, title);
            }
            writer.flush();
        }
        fixture.files.close();
        return index.sessions();
    }

    @Test
    void theDirectoryAndTheNewNameAreKeptAndRenamingIsNotActivity() throws Exception {
        try (SessionIndex index = SessionIndex.open(dir)) {
            scenario(index, "Quattro review");
            SessionIndex.Session s1 = index.session(S1).orElseThrow();
            assertEquals("C:\\APPL\\Quattro", s1.dir());
            assertEquals("Quattro review", s1.shownTitle());
            assertEquals("review the uncommitted code", s1.title());
            assertEquals(T1, s1.lastActivity(), "renaming does not move the session up");
            SessionIndex.Session s2 = index.session(S2).orElseThrow();
            assertNull(s2.dir());
            assertNull(s2.renamed());
        }
    }

    @Test
    void anEmptyNameRestoresTheAutomaticTitle() throws Exception {
        try (SessionIndex index = SessionIndex.open(dir)) {
            scenario(index, "Quattro review", "");
            SessionIndex.Session s1 = index.session(S1).orElseThrow();
            assertNull(s1.renamed());
            assertEquals("review the uncommitted code", s1.shownTitle());
        }
    }

    @Test
    void theLastNameWinsAndARebuildGivesTheSameRows() throws Exception {
        List<SessionIndex.Session> original;
        try (SessionIndex index = SessionIndex.open(dir)) {
            original = scenario(index, "first", "", "second");
            assertEquals("second", index.session(S1).orElseThrow().renamed());
        }
        for (String name : List.of(SessionIndex.FILE, SessionIndex.FILE + "-wal", SessionIndex.FILE + "-shm")) {
            Files.deleteIfExists(dir.resolve(name));
        }
        try (SessionIndex rebuilt = SessionIndex.open(dir)) {
            IndexCatchUp.run(rebuilt, new SessionPaths(dir), new SessionFileReader(EventCodec.withBuiltins()));
            assertEquals(original, rebuilt.sessions());
        }
    }
}
