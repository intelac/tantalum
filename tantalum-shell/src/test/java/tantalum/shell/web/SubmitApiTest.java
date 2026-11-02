package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

class SubmitApiTest {

    @TempDir
    Path temp;

    private TestServer server;
    private final CountDownLatch hold = new CountDownLatch(1);
    private final AtomicInteger calls = new AtomicInteger();

    private final ModelClient held = (prompt, chunks) -> {
        calls.incrementAndGet();
        try {
            hold.await();
        } catch (InterruptedException cancelled) {
            return new ModelOutcome.Interrupted("partial");
        }
        return new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);
    };

    @BeforeEach
    void start() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        server = new TestServer(temp.resolve("data"), workspace, held, "--tantalum.loop.max-user-in-flight=2");
    }

    @AfterEach
    void stop() {
        hold.countDown();
        server.close();
    }

    private HttpResponse<String> submit(String principal, String session, String requestId, String mode, String text) throws Exception {
        return server.post(principal, "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"" + requestId + "\",\"mode\":\"" + mode + "\",\"text\":\"" + text + "\"}");
    }

    private void waitForModelCall(int count) throws Exception {
        for (int i = 0; i < 500 && calls.get() < count; i++) {
            Thread.sleep(10);
        }
        assertEquals(count, calls.get());
    }

    @Test
    void submitReturnsWithoutWaitingForTheModel() throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> response = submit("alice", "s1", "r1", "prompt", "hi");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("submissionId"));
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5);
        waitForModelCall(1);
    }

    @Test
    void repeatedRequestIdDoesNotStartAnotherRun() throws Exception {
        String first = submit("alice", "s1", "r1", "prompt", "hi").body();
        waitForModelCall(1);
        HttpResponse<String> second = submit("alice", "s1", "r1", "prompt", "hi");
        assertEquals(200, second.statusCode());
        assertTrue(second.body().contains("\"duplicate\":true"), second.body());
        assertTrue(first.contains("\"duplicate\":false"), first);
        assertEquals(first.split("\"")[3], second.body().split("\"")[3]);
        Thread.sleep(100);
        assertEquals(1, calls.get());
    }

    @Test
    void missingRequestIdIsRejected() throws Exception {
        HttpResponse<String> response = server.post("alice", "/api/sessions/s1/submissions", "{\"mode\":\"prompt\",\"text\":\"hi\"}");
        assertEquals(400, response.statusCode());
    }

    @Test
    void badInputIsRejected() throws Exception {
        assertEquals(400, submit("alice", "s1", "r", "nonsense", "hi").statusCode());
        assertEquals(400, submit("alice", "s1", "r2", "prompt", "").statusCode());
        assertEquals(400, server.post("alice", "/api/sessions/s1/submissions", "{oops").statusCode());
    }

    @Test
    void notLoggedInIsRejected() throws Exception {
        assertEquals(401, submit(null, "s1", "r1", "prompt", "hi").statusCode());
    }

    @Test
    void anotherUsersSessionIsNotFound() throws Exception {
        submit("alice", "s1", "r1", "prompt", "hi");
        waitForModelCall(1);
        assertEquals(404, submit("bob", "s1", "r2", "prompt", "hi").statusCode());
    }

    @Test
    void cancelEndsTheRunAndInterruptsTheModel() throws Exception {
        submit("alice", "s1", "r1", "prompt", "hi");
        waitForModelCall(1);
        String run = runOf("alice", "s1");

        assertEquals(404, server.post("bob", "/api/runs/" + run + "/cancel", "{}").statusCode());
        assertEquals(200, server.post("alice", "/api/runs/" + run + "/cancel", "{}").statusCode());

        String body = "";
        for (int i = 0; i < 500 && !body.contains("CANCELLED"); i++) {
            Thread.sleep(10);
            body = server.get("alice", "/api/runs/" + run).body();
        }
        assertTrue(body.contains("CANCELLED"), body);
        assertEquals(404, server.post("alice", "/api/runs/nope/cancel", "{}").statusCode());
    }

    @Test
    void steerAndFollowUpAreAccepted() throws Exception {
        submit("alice", "s1", "r1", "prompt", "hi");
        waitForModelCall(1);
        assertEquals(200, submit("alice", "s1", "r2", "steer", "also").statusCode());
        assertEquals(200, submit("alice", "s1", "r3", "follow-up", "later").statusCode());
        String runs = "";
        for (int i = 0; i < 200 && !runs.contains("followUps\":1"); i++) {
            Thread.sleep(10);
            runs = server.get("alice", "/api/sessions/s1/runs").body();
        }
        assertTrue(runs.contains("followUps\":1"), runs);
    }

    private String runOf(String principal, String session) throws Exception {
        String body = "";
        for (int i = 0; i < 300 && !body.contains(session + "_"); i++) {
            Thread.sleep(10);
            body = server.get(principal, "/api/sessions/" + session + "/runs").body();
        }
        int start = body.indexOf(session + "_");
        return body.substring(start, body.indexOf('"', start));
    }
}
