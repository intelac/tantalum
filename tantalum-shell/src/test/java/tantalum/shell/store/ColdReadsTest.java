package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.loop.SessionSlice;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.SessionState;

class ColdReadsTest {

    private static final Principal ALICE = new Principal("alice");

    @TempDir
    Path dir;

    private final AtomicInteger reads = new AtomicInteger();

    private ColdReads cold() {
        return new ColdReads(new SessionPaths(dir), (principal, session) -> {
            reads.incrementAndGet();
            return Optional.of(new SessionSlice(SessionState.start(session, principal), List.of(),
                    new Transcript.Saved(List.of(), List.of(), java.util.Map.of(), 0, reads.get()), null, reads.get()));
        });
    }

    private Path file(String session, String content) throws Exception {
        Path file = new SessionPaths(dir).sessionFile(ALICE, new SessionId(session));
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void anUnchangedFileIsReadOnce() throws Exception {
        file("s", "header\n");
        ColdReads cold = cold();
        cold.read(ALICE, new SessionId("s"));
        cold.read(ALICE, new SessionId("s"));
        assertEquals(1, reads.get());
    }

    @Test
    void aGrownFileIsReadAgainSoNoStaleResult() throws Exception {
        Path file = file("s", "header\n");
        ColdReads cold = cold();
        assertEquals(1, cold.read(ALICE, new SessionId("s")).orElseThrow().seq());
        Files.writeString(file, "header\nevent\n");
        assertEquals(2, cold.read(ALICE, new SessionId("s")).orElseThrow().seq());
    }

    /** 别人的会话文件在别人的目录里：本人路径下不存在，直接为空，不重放任何东西。 */
    @Test
    void aMissingFileIsEmptyWithoutReading() throws Exception {
        file("s", "header\n");
        assertTrue(cold().read(new Principal("bob"), new SessionId("s")).isEmpty());
        assertEquals(0, reads.get());
    }

    @Test
    void onlyTheMostRecentSixteenAreKept() throws Exception {
        ColdReads cold = cold();
        for (int i = 0; i <= ColdReads.CAPACITY; i++) {
            file("s" + i, "header\n");
            cold.read(ALICE, new SessionId("s" + i));
        }
        cold.read(ALICE, new SessionId("s" + ColdReads.CAPACITY));
        assertEquals(ColdReads.CAPACITY + 1, reads.get(), "the newest is still cached");
        cold.read(ALICE, new SessionId("s0"));
        assertEquals(ColdReads.CAPACITY + 2, reads.get(), "the oldest was dropped");
    }
}
