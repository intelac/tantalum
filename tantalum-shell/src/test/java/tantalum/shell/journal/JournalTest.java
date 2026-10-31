package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.value.event.Cancel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

class JournalTest {

    @TempDir
    Path dir;

    private final EventCodec codec = EventCodec.withBuiltins();

    private static EventEnvelope event(long seq, RunId run) {
        return new EventEnvelope(seq, Instant.parse("2026-10-31T01:00:00Z"), new Principal("u"),
                new SessionId("s"), run, new Cancel(new RequestId("r" + seq)));
    }

    private void sql(String statement) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(Journal.FILE));
                Statement run = connection.createStatement()) {
            run.execute(statement);
        }
    }

    @Test
    void committedEventsSurviveReopenWithAllFields() throws Exception {
        EventEnvelope first = event(0, null);
        EventEnvelope second = event(1, new RunId("run-0"));
        try (Journal journal = Journal.open(dir, codec)) {
            journal.append(first);
            journal.append(second);
            journal.commit();
        }
        try (Journal journal = Journal.open(dir, codec)) {
            assertEquals(List.of(first, second), journal.readAll());
            assertEquals(2, journal.nextSeq());
        }
    }

    @Test
    void uncommittedEventsAreNotDurable() throws Exception {
        try (Journal journal = Journal.open(dir, codec)) {
            journal.append(event(0, null));
            journal.commit();
            journal.append(event(1, null));
        }
        try (Journal journal = Journal.open(dir, codec)) {
            assertEquals(1, journal.readAll().size());
        }
    }

    @Test
    void uncommittedEventsAreNotVisibleToReaders() throws Exception {
        try (Journal journal = Journal.open(dir, codec)) {
            journal.append(event(0, null));
            assertEquals(0, journal.readAll().size());
            journal.commit();
            assertEquals(1, journal.readAll().size());
        }
    }

    @Test
    void newJournalStartsAtZero() throws Exception {
        try (Journal journal = Journal.open(dir, codec)) {
            assertEquals(0, journal.nextSeq());
        }
    }

    @Test
    void gapInSequenceIsReportedWithItsPosition() throws Exception {
        try (Journal journal = Journal.open(dir, codec)) {
            for (long seq = 0; seq < 3; seq++) {
                journal.append(event(seq, null));
            }
            journal.commit();
        }
        sql("DELETE FROM events WHERE seq = 1");
        try (Journal journal = Journal.open(dir, codec)) {
            JournalCorruptException failure = assertThrows(JournalCorruptException.class, journal::verify);
            assertTrue(failure.getMessage().contains("seq 2"), failure.getMessage());
        }
    }

    @Test
    void unknownEventTypeIsReportedWithItsPosition() throws Exception {
        try (Journal journal = Journal.open(dir, codec)) {
            journal.append(event(0, null));
            journal.commit();
        }
        sql("UPDATE events SET type = 'Mystery' WHERE seq = 0");
        try (Journal journal = Journal.open(dir, codec)) {
            JournalCorruptException failure = assertThrows(JournalCorruptException.class, journal::verify);
            assertTrue(failure.getMessage().contains("seq 0"), failure.getMessage());
            assertTrue(failure.getMessage().contains("Mystery"), failure.getMessage());
        }
    }

    @Test
    void corruptPayloadIsReported() throws Exception {
        try (Journal journal = Journal.open(dir, codec)) {
            journal.append(event(0, null));
            journal.commit();
        }
        sql("UPDATE events SET payload = '{oops' WHERE seq = 0");
        try (Journal journal = Journal.open(dir, codec)) {
            assertThrows(JournalCorruptException.class, journal::verify);
        }
    }

    @Test
    void corruptFileIsRejected() throws Exception {
        Files.write(dir.resolve(Journal.FILE), "this is not a database, just some garbage bytes".repeat(100).getBytes());
        assertThrows(JournalCorruptException.class, () -> Journal.open(dir, codec).close());
    }

    @Test
    void filesAreOnlyAccessibleToTheOwner() throws Exception {
        try (Journal journal = Journal.open(dir, codec)) {
            journal.append(event(0, null));
            journal.commit();
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(Journal.FILE))));
            Path wal = dir.resolve(Journal.FILE + "-wal");
            assertTrue(Files.exists(wal));
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(wal)));
        }
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
    }
}
