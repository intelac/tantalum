package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.store.SessionFile;
import tantalum.shell.store.SessionPaths;
import tantalum.value.event.Cancel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;

class SequenceTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");

    @TempDir
    Path dir;

    private void write(Principal principal, String session, long seq) throws Exception {
        SessionId id = new SessionId(session);
        try (SessionFile file = SessionFile.open(new SessionPaths(dir), principal, id, EventCodec.withBuiltins())) {
            file.append(List.of(new EventEnvelope(seq, Instant.EPOCH, principal, id, null, new Cancel(new RequestId("r")))));
        }
    }

    private TailSequence sequence() {
        return new TailSequence(new SessionPaths(dir), 1);
    }

    @Test
    void newUserStartsAtZeroAndUsersAreIndependent() {
        TailSequence sequence = sequence();
        assertEquals(0, sequence.next(ALICE));
        assertEquals(1, sequence.next(ALICE));
        assertEquals(0, sequence.next(BOB));
        assertEquals(2, sequence.next(ALICE));
    }

    @Test
    void afterRestartContinuesAboveTheLargestSeqInAllOfTheUsersFiles() throws Exception {
        write(ALICE, "s1", 4);
        write(ALICE, "s2", 41);
        write(ALICE, "s3", 7);
        write(BOB, "s4", 900);
        assertEquals(42, sequence().next(ALICE));
    }

    @Test
    void incompleteTailDoesNotCount() throws Exception {
        write(ALICE, "s1", 4);
        Path file = new SessionPaths(dir).sessionFile(ALICE, new SessionId("s1"));
        Files.write(file, "{\"seq\":99,\"at".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        assertEquals(5, sequence().next(ALICE));
    }

    @Test
    void completeButInvalidLastLineDoesNotCount() throws Exception {
        write(ALICE, "s1", 4);
        Path file = new SessionPaths(dir).sessionFile(ALICE, new SessionId("s1"));
        Files.write(file, "{bad\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        assertEquals(5, sequence().next(ALICE));
    }

    /** 别人的文件坏了或读不了都不影响：证明根本没有读。 */
    @Test
    void onlyReadsThatUsersFiles() throws Exception {
        write(ALICE, "s1", 4);
        Path bob = new SessionPaths(dir).sessionFile(BOB, new SessionId("s2"));
        Files.createDirectories(bob.getParent());
        Files.writeString(bob, "garbage\n");
        Files.setPosixFilePermissions(bob, java.util.Set.of());
        assertEquals(5, sequence().next(ALICE));
    }

    @Test
    void overTheLimitFailsInsteadOfOverflowing() throws Exception {
        write(ALICE, "s1", Sequence.MAX - 1);
        TailSequence sequence = sequence();
        assertEquals(Sequence.MAX, sequence.next(ALICE));
        assertThrows(IllegalStateException.class, () -> sequence.next(ALICE));
        assertThrows(IllegalStateException.class, () -> sequence.next(ALICE));
    }
}
