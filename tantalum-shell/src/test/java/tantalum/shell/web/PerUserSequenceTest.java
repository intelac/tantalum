package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.id.RunId;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/** 需求 11 和 3.1：经过真实的 Web、环和文件。 */
class PerUserSequenceTest {

    @TempDir
    Path temp;

    private final ModelClient model = (prompt, chunks) ->
            new ModelOutcome.Replied(List.of(new TextBlock("answer")), StopReason.END_TURN, "m", null);

    private Path workspace() throws Exception {
        return Files.createDirectories(temp.resolve("workspace"));
    }

    private static String lastRun(TestServer server, String user, String session, int count) throws Exception {
        String body = "";
        for (int i = 0; i < 500; i++) {
            body = server.get(user, "/api/sessions/" + session + "/runs").body();
            if (body.split("COMPLETED", -1).length - 1 == count) {
                int start = body.lastIndexOf("\"" + session + "_") + 1;
                return body.substring(start, body.indexOf('"', start));
            }
            Thread.sleep(10);
        }
        throw new AssertionError(body);
    }

    private static void submit(TestServer server, String user, String session, String request) throws Exception {
        assertEquals(200, server.post(user, "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"" + request + "\",\"mode\":\"prompt\",\"text\":\"secret of " + user + "\"}").statusCode());
    }

    @Test
    void twoUsersWithTheSameSeqDoNotMix() throws Exception {
        try (TestServer server = new TestServer(temp.resolve("data"), workspace(), model)) {
            submit(server, "alice", "a1", "r");
            submit(server, "bob", "b1", "r");
            String alice = lastRun(server, "alice", "a1", 1);
            String bob = lastRun(server, "bob", "b1", 1);
            assertEquals(new RunId(alice).seq(), new RunId(bob).seq(), "each user's first event has the same seq");

            String aliceTurn = server.get("alice", "/api/runs/" + alice + "/turns/1").body();
            String bobTurn = server.get("bob", "/api/runs/" + bob + "/turns/1").body();
            assertTrue(aliceTurn.contains("secret of alice") && !aliceTurn.contains("secret of bob"), aliceTurn);
            assertTrue(bobTurn.contains("secret of bob") && !bobTurn.contains("secret of alice"), bobTurn);
            assertEquals(404, server.get("bob", "/api/runs/" + alice).statusCode());
            assertEquals(404, server.get("alice", "/api/runs/" + bob).statusCode());
            String messages = server.get("alice", "/api/sessions/a1/messages").body();
            assertFalse(messages.contains("secret of bob"), messages);
        }
    }

    @Test
    void afterRestartNewSeqIsAboveEverythingTheUserHasAndHistoryIsKept() throws Exception {
        Path data = temp.resolve("data");
        String first;
        try (TestServer server = new TestServer(data, workspace(), model)) {
            submit(server, "alice", "a1", "r1");
            first = lastRun(server, "alice", "a1", 1);
            submit(server, "alice", "a2", "r2");
            lastRun(server, "alice", "a2", 1);
        }
        try (TestServer server = new TestServer(data, workspace(), model)) {
            assertEquals(200, server.get("alice", "/api/runs/" + first).statusCode());
            submit(server, "alice", "a3", "r3");
            String next = lastRun(server, "alice", "a3", 1);
            assertTrue(new RunId(next).seq() > new RunId(first).seq() + 3, next);
            submit(server, "alice", "a1", "r1-again");
            assertEquals(1, server.get("alice", "/api/sessions/a1/runs").body().split("COMPLETED", -1).length - 1);
        }
    }

    @Test
    void oldJournalIsRefusedAndLeftUntouched() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        Files.writeString(data.resolve("journal.db"), "old data");
        Throwable failure = assertThrows(Exception.class, () -> new TestServer(data, workspace(), model));
        boolean explained = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            explained |= String.valueOf(cause.getMessage()).contains("journal.db");
        }
        assertTrue(explained, failure.toString());
        assertEquals("old data", Files.readString(data.resolve("journal.db")));
        assertFalse(Files.exists(data.resolve("format.json")));
    }
}
