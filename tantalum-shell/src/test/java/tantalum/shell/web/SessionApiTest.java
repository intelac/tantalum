package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SessionApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private TestServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    /** 提问为 {@code slow} 时等到测试放行，让那个会话停在运行中。 */
    private final ModelClient model = (prompt, chunks) -> {
        TextBlock last = (TextBlock) prompt.messages().getLast().content().getFirst();
        if (last.text().equals("slow")) {
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);
    };

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("workspace")), model);
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.close();
    }

    private void completed(String user, String session, String text) throws Exception {
        server.post(user, "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"" + session + "\",\"mode\":\"prompt\",\"text\":\"" + text + "\"}");
        String body = "";
        for (int i = 0; i < 500 && !body.contains("COMPLETED"); i++) {
            Thread.sleep(10);
            body = server.get(user, "/api/sessions/" + session + "/runs").body();
        }
        assertTrue(body.contains("COMPLETED"), body);
    }

    @Test
    void listsOwnSessionsMostRecentFirstWithTitleAndStatus() throws Exception {
        completed("alice", "s1", "first question about interest\\nsecond line");
        Thread.sleep(5);
        completed("alice", "s2", "second");
        server.post("alice", "/api/sessions/s3/submissions", "{\"requestId\":\"s3\",\"mode\":\"prompt\",\"text\":\"slow\"}");
        completed("bob", "b1", "bob's own");

        HttpResponse<String> response = server.get("alice", "/api/sessions");
        assertEquals(200, response.statusCode());
        JsonNode sessions = JSON.readTree(response.body()).get("sessions");
        assertEquals(3, sessions.size());
        assertEquals(List.of("s3", "s2", "s1"), List.of(sessions.get(0).get("id").asString(),
                sessions.get(1).get("id").asString(), sessions.get(2).get("id").asString()));
        assertEquals("first question about interest", sessions.get(2).get("title").asString());
        assertEquals("idle", sessions.get(2).get("runStatus").asString());
        assertEquals("AwaitingModel", sessions.get(0).get("runStatus").asString());
        assertEquals(false, sessions.get(0).get("pendingRecovery").asBoolean());
        assertTrue(sessions.get(0).get("lastActivity").asString().endsWith("Z"));

        JsonNode bobs = JSON.readTree(server.get("bob", "/api/sessions").body()).get("sessions");
        assertEquals(1, bobs.size());
        assertEquals("b1", bobs.get(0).get("id").asString());
    }

    @Test
    void newUserHasNoSessions() throws Exception {
        assertEquals("{\"sessions\":[]}", server.get("carol", "/api/sessions").body());
    }
}
