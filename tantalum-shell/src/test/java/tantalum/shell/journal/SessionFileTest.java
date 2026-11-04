package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.OwnerRef;

class SessionFileTest {

    private static final Principal ALICE = new Principal("alice");
    private static final SessionId S1 = new SessionId("s-1");
    private final EventCodec codec = EventCodec.withBuiltins();

    @TempDir
    Path dir;

    static EventEnvelope event(Principal principal, SessionId session, long seq, String text) {
        return new EventEnvelope(seq, Instant.parse("2026-11-02T00:00:00.123456Z"), principal, session,
                seq % 2 == 0 ? null : new RunId(session.value() + "_" + seq),
                new UserPrompt(new SubmissionId("sub-" + seq), new RequestId("req-" + seq), new OwnerRef.OfSession(session), text));
    }

    @Test
    void writtenEventsReadBackEqual() throws Exception {
        SessionPaths paths = new SessionPaths(dir);
        List<EventEnvelope> events = List.of(event(ALICE, S1, 3, "第一行\n第二行 \"引号\"   😀"), event(ALICE, S1, 7, "x"));
        try (SessionFile file = SessionFile.open(paths, ALICE, S1, codec)) {
            file.append(events);
        }
        assertEquals(events, new SessionFileReader(codec).readAll(paths.sessionFile(ALICE, S1)));
    }

    @Test
    void headerIsFirstLineAndEachEventIsOneLine() throws Exception {
        SessionPaths paths = new SessionPaths(dir);
        try (SessionFile file = SessionFile.open(paths, ALICE, S1, codec)) {
            file.append(List.of(event(ALICE, S1, 1, "a\nb")));
        }
        List<String> lines = Files.readAllLines(paths.sessionFile(ALICE, S1));
        assertEquals(2, lines.size());
        assertEquals("{\"format\":2,\"session\":\"s-1\",\"principal\":\"alice\"}", lines.get(0));
        assertTrue(lines.get(1).startsWith("{\"seq\":1,\"at\":\"2026-11-02T00:00:00.123456Z\",\"type\":\"UserPrompt\",\"version\":1,\"run\":\"s-1_1\",\"payload\":{"), lines.get(1));
    }

    @Test
    void permissionsAreOwnerOnly() throws Exception {
        SessionPaths paths = new SessionPaths(dir);
        SessionFile.open(paths, ALICE, S1, codec).close();
        Path file = paths.sessionFile(ALICE, S1);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.sessionsDir())));
    }

    @Test
    void reopeningAppendsAndDoesNotRepeatHeader() throws Exception {
        SessionPaths paths = new SessionPaths(dir);
        try (SessionFile file = SessionFile.open(paths, ALICE, S1, codec)) {
            file.append(List.of(event(ALICE, S1, 1, "a")));
        }
        try (SessionFile file = SessionFile.open(paths, ALICE, S1, codec)) {
            file.append(List.of(event(ALICE, S1, 2, "b")));
        }
        Path path = paths.sessionFile(ALICE, S1);
        assertEquals(3, Files.readAllLines(path).size());
        assertEquals(2, new SessionFileReader(codec).readAll(path).size());
    }

    @Test
    void eventOfAnotherSessionOrUserIsRejected() throws Exception {
        SessionPaths paths = new SessionPaths(dir);
        try (SessionFile file = SessionFile.open(paths, ALICE, S1, codec)) {
            assertThrows(IllegalArgumentException.class, () -> file.append(List.of(event(ALICE, new SessionId("other"), 1, "x"))));
            assertThrows(IllegalArgumentException.class, () -> file.append(List.of(event(new Principal("bob"), S1, 1, "x"))));
        }
        assertEquals(1, Files.readAllLines(paths.sessionFile(ALICE, S1)).size());
    }

    @Test
    void noFileUntilFirstWrite() throws Exception {
        SessionPaths paths = new SessionPaths(dir);
        try (SessionFiles files = new SessionFiles(paths, codec, 4, (principal, session, seq, offset) -> { })) {
            assertFalse(Files.exists(paths.sessionFile(ALICE, S1)));
            files.append(ALICE, S1, List.of(event(ALICE, S1, 1, "x")));
            assertTrue(Files.exists(paths.sessionFile(ALICE, S1)));
        }
    }

    @Test
    void writingAfterHandleWasEvictedStillAppends() throws Exception {
        SessionPaths paths = new SessionPaths(dir);
        SessionId s2 = new SessionId("s-2");
        try (SessionFiles files = new SessionFiles(paths, codec, 1, (principal, session, seq, offset) -> { })) {
            files.append(ALICE, S1, List.of(event(ALICE, S1, 1, "a")));
            files.append(ALICE, s2, List.of(event(ALICE, s2, 2, "b")));
            assertEquals(1, files.openCount());
            files.append(ALICE, S1, List.of(event(ALICE, S1, 3, "c")));
            assertEquals(1, files.openCount());
        }
        SessionFileReader reader = new SessionFileReader(codec);
        assertEquals(List.of(event(ALICE, S1, 1, "a"), event(ALICE, S1, 3, "c")), reader.readAll(paths.sessionFile(ALICE, S1)));
        assertEquals(List.of(event(ALICE, s2, 2, "b")), reader.readAll(paths.sessionFile(ALICE, s2)));
    }

    @Test
    void manySessionsWriteConcurrentlyThroughFewHandles() throws Exception {
        SessionPaths paths = new SessionPaths(dir);
        int sessions = 40;
        int perSession = 25;
        try (SessionFiles files = new SessionFiles(paths, codec, 3, (principal, session, seq, offset) -> { });
                ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running = new ArrayList<>();
            for (int s = 0; s < sessions; s++) {
                SessionId id = new SessionId("s-" + s);
                running.add(writers.submit(() -> {
                    for (int i = 0; i < perSession; i++) {
                        files.append(ALICE, id, List.of(event(ALICE, id, i, "t" + i)));
                    }
                    return null;
                }));
            }
            for (Future<?> writer : running) {
                writer.get();
            }
            assertTrue(files.openCount() <= 3);
        }
        SessionFileReader reader = new SessionFileReader(codec);
        for (int s = 0; s < sessions; s++) {
            SessionId id = new SessionId("s-" + s);
            List<Long> seqs = reader.readAll(paths.sessionFile(ALICE, id)).stream().map(EventEnvelope::seq).toList();
            assertEquals(java.util.stream.LongStream.range(0, perSession).boxed().toList(), seqs);
        }
    }

    @Test
    void openRepairsTailBeforeAppending() throws Exception {
        SessionPaths paths = new SessionPaths(dir);
        try (SessionFile file = SessionFile.open(paths, ALICE, S1, codec)) {
            file.append(List.of(event(ALICE, S1, 1, "a")));
        }
        Path path = paths.sessionFile(ALICE, S1);
        Files.write(path, "{\"seq\":2,\"at".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        try (SessionFile file = SessionFile.open(paths, ALICE, S1, codec)) {
            file.append(List.of(event(ALICE, S1, 3, "c")));
        }
        assertEquals(List.of(event(ALICE, S1, 1, "a"), event(ALICE, S1, 3, "c")), new SessionFileReader(codec).readAll(path));
    }
}
