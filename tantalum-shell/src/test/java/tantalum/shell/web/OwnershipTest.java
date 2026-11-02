package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.store.SessionPaths;
import tantalum.value.model.TextBlock;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.model.StopReason;

/** 需求 2.8：同一个新会话 ID 只属于一个用户。这些用例走真实 HTTP 和真实文件。 */
class OwnershipTest {

    private static final ModelClient MODEL = (prompt, chunks) ->
            new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);

    @TempDir
    Path temp;

    private TestServer server() throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        return new TestServer(temp.resolve("data"), workspace, MODEL);
    }

    private static String body(String request) {
        return "{\"requestId\":\"" + request + "\",\"mode\":\"prompt\",\"text\":\"hi\"}";
    }

    @Test
    void concurrentUsersRacingForNewSessionIdsHaveOneWinnerEach() throws Exception {
        try (TestServer server = server(); ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            server.post("alice", "/api/sessions/warm-a/submissions", body("warm"));
            server.post("bob", "/api/sessions/warm-b/submissions", body("warm"));
            SessionPaths paths = new SessionPaths(temp.resolve("data"));
            for (int round = 0; round < 25; round++) {
                String id = "race-" + round;
                CountDownLatch start = new CountDownLatch(1);
                List<Future<HttpResponse<String>>> posts = new ArrayList<>();
                for (String user : List.of("alice", "bob")) {
                    String request = "r" + round;
                    posts.add(pool.submit(() -> {
                        start.await();
                        return server.post(user, "/api/sessions/" + id + "/submissions", body(request));
                    }));
                }
                start.countDown();
                int alice = posts.get(0).get().statusCode();
                int bob = posts.get(1).get().statusCode();
                assertTrue((alice == 200 && bob == 404) || (alice == 404 && bob == 200), id + ": " + alice + "/" + bob);
                String loser = alice == 200 ? "bob" : "alice";
                String winner = alice == 200 ? "alice" : "bob";
                SessionId session = new SessionId(id);
                for (int i = 0; i < 500 && !Files.exists(paths.sessionFile(new Principal(winner), session)); i++) {
                    Thread.sleep(5);
                }
                assertTrue(Files.exists(paths.sessionFile(new Principal(winner), session)));
                assertFalse(Files.exists(paths.sessionFile(new Principal(loser), session)), "the loser wrote no event");
            }
        }
    }

    @Test
    void ownershipAndDuplicatesSurviveARestart() throws Exception {
        String submission;
        try (TestServer server = server()) {
            HttpResponse<String> first = server.post("alice", "/api/sessions/s1/submissions", body("r1"));
            assertEquals(200, first.statusCode());
            submission = first.body();
            assertTrue(submission.contains("\"duplicate\":false"), submission);
            waitForCompleted(server);
        }
        try (TestServer server = server()) {
            assertEquals(404, server.post("bob", "/api/sessions/s1/submissions", body("r2")).statusCode());
            HttpResponse<String> again = server.post("alice", "/api/sessions/s1/submissions", body("r1"));
            assertEquals(submission.replace("\"duplicate\":false", "\"duplicate\":true"), again.body());
        }
    }

    private static void waitForCompleted(TestServer server) throws Exception {
        String body = "";
        for (int i = 0; i < 500 && !body.contains("COMPLETED"); i++) {
            Thread.sleep(10);
            body = server.get("alice", "/api/sessions/s1/runs").body();
        }
        assertTrue(body.contains("COMPLETED"), body);
    }
}
