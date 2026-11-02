package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.journal.EventCodec;
import tantalum.value.event.Cancel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.state.SessionState;

class SnapshotWriterTest {

    private static final Principal ALICE = new Principal("alice");
    private static final SessionId S = new SessionId("s");
    private static final Transcript.Part EMPTY = new Transcript.Part(List.of(), List.of(), java.util.Map.of(), 0, -1);

    @TempDir
    Path temp;

    private SessionPaths paths() {
        return new SessionPaths(temp);
    }

    private static EventEnvelope event(long seq) {
        return new EventEnvelope(seq, java.time.Instant.EPOCH, ALICE, S, null, new Cancel(new RequestId("r" + seq)));
    }

    private long append(long... seqs) throws Exception {
        try (SessionFile file = SessionFile.open(paths(), ALICE, S, EventCodec.withBuiltins())) {
            long size = 0;
            for (long seq : seqs) {
                size = file.append(List.of(event(seq)));
            }
            return size;
        }
    }

    private SnapshotStore store() {
        return new SnapshotStore(paths(), EventCodec.withBuiltins());
    }

    /** 文件已经比快照走得更远（运行结束后马上又来了事件）时，位置仍是快照那个序号的行尾。 */
    @Test
    void theOffsetIsTheEndOfTheSnapshotSeqEvenWhenTheFileHasMovedOn() throws Exception {
        long atFive = append(1, 5);
        append(9);
        try (SnapshotWriter writer = new SnapshotWriter(store(), paths())) {
            writer.offer(SessionState.start(S, ALICE), List.of(), EMPTY, 5);
            writer.flush();
        }
        SessionSnapshot loaded = store().load(ALICE, S, Long.MAX_VALUE).orElseThrow();
        assertEquals(5, loaded.seq());
        assertEquals(atFive, loaded.offset());
    }

    @Test
    void filesArePrivateAndNoTempFileIsLeft() throws Exception {
        append(1);
        try (SnapshotWriter writer = new SnapshotWriter(store(), paths())) {
            writer.offer(SessionState.start(S, ALICE), List.of(), EMPTY, 1);
            writer.flush();
        }
        Path file = paths().snapshotFile(ALICE, S);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
        try (var names = Files.list(file.getParent())) {
            assertEquals(1, names.count());
        }
    }

    @Test
    void aFailedWriteKeepsTheOldSnapshotAndTheWriterKeepsWorking() throws Exception {
        append(1, 2);
        Path file = paths().snapshotFile(ALICE, S);
        try (SnapshotWriter writer = new SnapshotWriter(store(), paths())) {
            writer.offer(SessionState.start(S, ALICE), List.of(), EMPTY, 1);
            writer.flush();
            Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("r-x------"));
            writer.offer(SessionState.start(S, ALICE), List.of(), EMPTY, 2);
            writer.flush();
            assertEquals(1, store().load(ALICE, S, Long.MAX_VALUE).orElseThrow().seq(), "the old snapshot survives");
            Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("rwx------"));
            writer.offer(SessionState.start(S, ALICE), List.of(), EMPTY, 2);
            writer.flush();
            assertEquals(2, store().load(ALICE, S, Long.MAX_VALUE).orElseThrow().seq());
        }
    }

    /** 没有这个序号时不写：宁可全量重放，也不写一个位置对不上的快照。 */
    @Test
    void aSeqNotInTheFileIsSkipped() throws Exception {
        append(1, 5);
        try (SnapshotWriter writer = new SnapshotWriter(store(), paths())) {
            writer.offer(SessionState.start(S, ALICE), List.of(), EMPTY, 3);
            writer.flush();
        }
        assertTrue(Files.notExists(paths().snapshotFile(ALICE, S)));
    }

    /** 写到一半崩溃只会留下临时文件。它不影响旧快照，也不影响没有快照时的全量重放。 */
    @Test
    void aCrashMidWriteLeavesOnlyATempFile() throws Exception {
        append(1, 2);
        try (SnapshotWriter writer = new SnapshotWriter(store(), paths())) {
            writer.offer(SessionState.start(S, ALICE), List.of(), EMPTY, 1);
            writer.flush();
        }
        Path file = paths().snapshotFile(ALICE, S);
        Files.writeString(file.getParent().resolve("snapshot123.tmp"), "{\"storage\":1,\"sem");
        assertEquals(1, store().load(ALICE, S, Long.MAX_VALUE).orElseThrow().seq());
        Files.delete(file);
        assertTrue(store().load(ALICE, S, Long.MAX_VALUE).isEmpty());
    }
}
