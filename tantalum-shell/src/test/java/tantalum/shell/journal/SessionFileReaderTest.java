package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

class SessionFileReaderTest {

    private static final Principal ALICE = new Principal("alice");
    private static final SessionId S1 = new SessionId("s-1");
    private final EventCodec codec = EventCodec.withBuiltins();
    private final SessionFileReader reader = new SessionFileReader(codec);

    @TempDir
    Path dir;

    private Path threeEvents(List<EventEnvelope> events) throws Exception {
        SessionPaths paths = new SessionPaths(dir);
        try (SessionFile file = SessionFile.open(paths, ALICE, S1, codec)) {
            file.append(events);
        }
        return paths.sessionFile(ALICE, S1);
    }

    private static List<EventEnvelope> sample() {
        return List.of(SessionFileTest.event(ALICE, S1, 1, "一二三"), SessionFileTest.event(ALICE, S1, 2, "b"), SessionFileTest.event(ALICE, S1, 5, "😀"));
    }

    /** 每个长度下，读取和截尾都得到全部完整的事件，截尾后还能继续写。这是崩溃能留下的所有形态。 */
    @Test
    void everyByteTruncationKeepsAllCompleteEvents() throws Exception {
        List<EventEnvelope> events = sample();
        Path path = threeEvents(events);
        byte[] whole = Files.readAllBytes(path);
        List<Integer> newlines = new ArrayList<>();
        for (int i = 0; i < whole.length; i++) {
            if (whole[i] == '\n') {
                newlines.add(i);
            }
        }
        for (int length = 0; length <= whole.length; length++) {
            Files.write(path, Arrays.copyOf(whole, length));
            int finished = length;
            int complete = Math.max(0, (int) newlines.stream().filter(at -> at < finished).count() - 1);
            List<EventEnvelope> expected = events.subList(0, complete);
            assertEquals(expected, reader.readAll(path), "read at length " + length);

            byte[] truncated = Files.readAllBytes(path);
            SessionFileReader.repairTail(path);
            assertEquals(expected, reader.readAll(path), "repair at length " + length);
            int kept = (int) Files.size(path);
            assertArrayEquals(Arrays.copyOf(truncated, kept), Files.readAllBytes(path), "repair only cuts the tail");
            assertTrue(kept == 0 || whole[kept - 1] == '\n', "repair stops at a line end, length " + length);

            EventEnvelope next = SessionFileTest.event(ALICE, S1, 9, "next");
            try (SessionFile file = SessionFile.open(new SessionPaths(dir), ALICE, S1, codec)) {
                file.append(List.of(next));
            }
            List<EventEnvelope> continued = new ArrayList<>(expected);
            continued.add(next);
            assertEquals(continued, reader.readAll(path), "append at length " + length);
        }
    }

    @Test
    void readingDoesNotModifyAnIncompleteFile() throws Exception {
        Path path = threeEvents(sample());
        byte[] whole = Files.readAllBytes(path);
        byte[] partial = Arrays.copyOf(whole, whole.length - 3);
        Files.write(path, partial);
        reader.readAll(path);
        assertArrayEquals(partial, Files.readAllBytes(path));
    }

    @Test
    void completeButInvalidLastLineIsDroppedAndRepaired() throws Exception {
        Path path = threeEvents(sample());
        Files.write(path, "garbage\n".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        assertEquals(sample(), reader.readAll(path));
        SessionFileReader.repairTail(path);
        assertEquals(4, Files.readAllLines(path).size());
    }

    @Test
    void tailLongerThanOneScanChunkIsCut() throws Exception {
        Path path = threeEvents(sample());
        long good = Files.size(path);
        Files.write(path, ("{\"seq\":6,\"text\":\"" + "x".repeat(200_000)).getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        SessionFileReader.repairTail(path);
        assertEquals(good, Files.size(path));
    }

    @Test
    void corruptionInTheMiddleIsRejectedWithLineNumber() throws Exception {
        Path path = threeEvents(sample());
        List<String> lines = new ArrayList<>(Files.readAllLines(path));
        lines.set(2, "{broken");
        Files.write(path, lines);
        SessionCorruptException failure = assertThrows(SessionCorruptException.class, () -> reader.readAll(path));
        assertTrue(failure.getMessage().contains("line 3"), failure.getMessage());
    }

    @Test
    void invalidUtf8InTheMiddleIsRejected() throws Exception {
        Path path = threeEvents(sample());
        byte[] whole = Files.readAllBytes(path);
        int secondLine = new String(whole, StandardCharsets.ISO_8859_1).indexOf('\n') + 1;
        whole[secondLine + 1] = (byte) 0xFF;
        Files.write(path, whole);
        assertThrows(SessionCorruptException.class, () -> reader.readAll(path));
    }

    @Test
    void seqMustIncreaseWithinAFileButMayJump() throws Exception {
        Path path = threeEvents(List.of(SessionFileTest.event(ALICE, S1, 2, "a"), SessionFileTest.event(ALICE, S1, 900_000, "b")));
        assertEquals(2, reader.readAll(path).size());
        try (SessionFile file = SessionFile.open(new SessionPaths(dir), ALICE, S1, codec)) {
            file.append(List.of(SessionFileTest.event(ALICE, S1, 5, "goes back"), SessionFileTest.event(ALICE, S1, 900_001, "c")));
        }
        SessionCorruptException failure = assertThrows(SessionCorruptException.class, () -> reader.readAll(path));
        assertTrue(failure.getMessage().contains("line 4"), failure.getMessage());
    }

    @Test
    void unknownEventTypeIsRejectedNotSkippedAndNotCutFromTail() throws Exception {
        Path path = threeEvents(sample());
        String future = "{\"seq\":9,\"at\":\"2026-11-02T00:00:00Z\",\"type\":\"FromTheFuture\",\"version\":1,\"run\":null,\"payload\":{}}\n";
        Files.write(path, future.getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        long size = Files.size(path);
        SessionFileReader.repairTail(path);
        assertEquals(size, Files.size(path));
        assertThrows(SessionCorruptException.class, () -> reader.readAll(path));
    }

    @Test
    void newerFileFormatIsRejected() throws Exception {
        Path path = threeEvents(sample());
        List<String> lines = new ArrayList<>(Files.readAllLines(path));
        lines.set(0, "{\"format\":3,\"session\":\"s-1\",\"principal\":\"alice\"}");
        Files.write(path, lines);
        assertThrows(SessionCorruptException.class, () -> reader.readAll(path));
    }

    @Test
    void corruptSessionDoesNotAffectAnother() throws Exception {
        Path path = threeEvents(sample());
        SessionId other = new SessionId("s-2");
        EventEnvelope event = SessionFileTest.event(ALICE, other, 1, "fine");
        SessionPaths paths = new SessionPaths(dir);
        try (SessionFile file = SessionFile.open(paths, ALICE, other, codec)) {
            file.append(List.of(event));
        }
        Files.writeString(path, "{\"format\":2,\"session\":\"s-1\",\"principal\":\"alice\"}\n{bad\n{\"seq\":1}\n");
        assertThrows(SessionCorruptException.class, () -> reader.readAll(path));
        assertEquals(List.of(event), reader.readAll(paths.sessionFile(ALICE, other)));
    }

    @Test
    void missingFileHasNoEvents() throws Exception {
        assertEquals(List.of(), reader.readAll(dir.resolve("nope.jsonl")));
    }
}
