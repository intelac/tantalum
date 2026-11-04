package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.value.decision.SessionChange;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.Message;
import tantalum.value.state.OwnerRef;

/** record-results 任务 5：事件行和结果行交错写进同一个文件，按顺序读回，结果行能引用前面的正文。 */
class ResultLinesTest {

    private static final Principal USER = new Principal("alice");
    private static final SessionId SESSION = new SessionId("s-1");
    private static final String LONG = "a long question ".repeat(30);

    @TempDir
    Path dir;

    private final EventCodec codec = EventCodec.withBuiltins();

    private static EventEnvelope prompt(long seq, String text) {
        return new EventEnvelope(seq, Instant.EPOCH, USER, SESSION, null,
                new UserPrompt(new SubmissionId("sub-" + seq), new RequestId("req-" + seq), new OwnerRef.OfSession(SESSION), text));
    }

    private static StepRecord appended(long seq, String text) {
        return new StepRecord(seq, new SessionChange(SESSION, USER, seq == 0, List.of(Message.user(text)),
                null, null, null, null, null, null, null), List.of(), List.of(), null, null, List.of(), List.of());
    }

    private SessionFiles files() {
        return new SessionFiles(new SessionPaths(dir), codec, 4, (principal, session, seq, offset) -> { });
    }

    private Path file() {
        return new SessionPaths(dir).sessionFile(USER, SESSION);
    }

    @Test
    void eventsAndResultsInterleaveAndReadBackInOrder() throws Exception {
        try (SessionFiles files = files()) {
            files.appendLines(USER, SESSION, List.of(new Line.Event(prompt(0, LONG)), new Line.Event(prompt(1, "steer"))));
            files.appendLines(USER, SESSION, List.of(new Line.Result(appended(0, LONG)), new Line.Result(appended(1, "steer"))));
        }
        assertEquals(2, Files.readString(file()).split(LONG, -1).length,
                "the long question is written once, in the event line; the result line refers to it");

        List<Line> read = new ArrayList<>();
        new SessionFileReader(codec).forEachLine(file(), read::add);

        assertEquals(List.of(new Line.Event(prompt(0, LONG)), new Line.Event(prompt(1, "steer")),
                new Line.Result(appended(0, LONG)), new Line.Result(appended(1, "steer"))), read);
        assertEquals(List.of(prompt(0, LONG), prompt(1, "steer")), new SessionFileReader(codec).readAll(file()));
    }

    @Test
    void theMemoFromReadingLetsTheNextWriterReferenceTheFile() throws Exception {
        try (SessionFiles files = files()) {
            files.appendLines(USER, SESSION, List.of(new Line.Event(prompt(0, LONG)), new Line.Result(appended(0, LONG))));
        }
        RecordMemo memo = new SessionFileReader(codec).forEachLine(file(), line -> { }).toWriter();
        long before = Files.size(file());
        try (SessionFiles files = files()) {
            files.adopt(USER, SESSION, memo);
            files.appendLines(USER, SESSION, List.of(new Line.Event(prompt(1, "again")), new Line.Result(appended(1, LONG))));
        }
        String added = Files.readString(file()).substring((int) before);
        assertFalse(added.contains(LONG), added);

        List<Line> read = new ArrayList<>();
        new SessionFileReader(codec).forEachLine(file(), read::add);
        assertEquals(new Line.Result(appended(1, LONG)), read.getLast());
    }

    @Test
    void aWriterThatDidNotReadTheFileWritesEverythingOut() throws Exception {
        try (SessionFiles files = files()) {
            files.appendLines(USER, SESSION, List.of(new Line.Event(prompt(0, LONG)), new Line.Result(appended(0, LONG))));
        }
        long before = Files.size(file());
        try (SessionFiles files = files()) {
            files.appendLines(USER, SESSION, List.of(new Line.Event(prompt(1, "again")), new Line.Result(appended(1, LONG))));
        }
        assertTrue(Files.readString(file()).substring((int) before).contains(LONG));
    }

    @Test
    void aResultNeedsAnEarlierEventWithoutAResult() throws Exception {
        try (SessionFiles files = files()) {
            files.appendLines(USER, SESSION, List.of(new Line.Event(prompt(0, "q")), new Line.Result(appended(0, "q"))));
        }
        List<String> lines = Files.readAllLines(file());
        SessionFileReader reader = new SessionFileReader(codec);

        Files.write(file(), List.of(lines.get(0), lines.get(2), lines.get(1)));
        assertThrows(SessionCorruptException.class, () -> reader.forEachLine(file(), line -> { }));

        Files.write(file(), List.of(lines.get(0), lines.get(1), lines.get(2), lines.get(2)));
        assertThrows(SessionCorruptException.class, () -> reader.forEachLine(file(), line -> { }));
    }

    @Test
    void aFormatOneFileIsNotRead() throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "{\"format\":1,\"session\":\"s-1\",\"principal\":\"alice\"}\n"
                + "{\"seq\":0,\"at\":\"1970-01-01T00:00:00Z\",\"type\":\"RunInterrupted\",\"version\":1,\"run\":null,\"payload\":{}}\n");

        SessionCorruptException failure = assertThrows(SessionCorruptException.class,
                () -> new SessionFileReader(codec).readAll(file()));
        assertTrue(failure.getMessage().contains("format 1"), failure.getMessage());
    }
}
