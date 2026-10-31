package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.Journal;
import tantalum.value.event.Cancel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;

class JournalWriterTest {

    @TempDir
    Path dir;

    private static InputRing.Slot slot() {
        InputRing.Slot slot = new InputRing.Slot();
        slot.envelope = new EventEnvelope(EventEnvelope.UNASSIGNED, Instant.EPOCH, new Principal("u"),
                new SessionId("s"), null, new Cancel(new RequestId("r")));
        return slot;
    }

    @Test
    void assignsSequenceAndCommitsAtEndOfBatch() throws Exception {
        try (Journal journal = Journal.open(dir, EventCodec.withBuiltins())) {
            JournalWriter writer = new JournalWriter(journal, 5);
            InputRing.Slot first = slot();
            InputRing.Slot second = slot();

            writer.onEvent(first, 0, false);
            assertEquals(5, first.envelope.seq());
            assertEquals(0, journal.readAll().size());

            writer.onEvent(second, 1, true);
            assertEquals(6, second.envelope.seq());
        }
    }

    @Test
    void committedBatchIsReadable() throws Exception {
        try (Journal journal = Journal.open(dir, EventCodec.withBuiltins())) {
            JournalWriter writer = new JournalWriter(journal, 0);
            writer.onEvent(slot(), 0, false);
            writer.onEvent(slot(), 1, true);
            assertEquals(2, journal.readAll().size());
            assertFalse(writer.halted());
        }
    }

    @Test
    void writeFailureHaltsAndLaterEventsAreIgnored() throws Exception {
        Journal journal = Journal.open(dir, EventCodec.withBuiltins());
        JournalWriter writer = new JournalWriter(journal, 0);
        journal.close();

        writer.onEvent(slot(), 0, true);
        assertTrue(writer.halted());

        InputRing.Slot later = slot();
        writer.onEvent(later, 1, true);
        assertEquals(EventEnvelope.UNASSIGNED, later.envelope.seq());
    }
}
