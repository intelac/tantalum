package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

class QueryApiTest {

    @TempDir
    Path temp;

    private TestServer server;

    private final ModelClient model = (prompt, chunks) -> {
        chunks.onText("Hel");
        chunks.onText("lo");
        return new ModelOutcome.Replied(List.of(new TextBlock("Hello")), StopReason.END_TURN, "m", null);
    };

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("workspace")), model);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private String finishedRun() throws Exception {
        server.post("alice", "/api/sessions/s1/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"hi\"}");
        String body = "";
        for (int i = 0; i < 500 && !body.contains("COMPLETED"); i++) {
            Thread.sleep(10);
            body = server.get("alice", "/api/sessions/s1/runs").body();
        }
        assertTrue(body.contains("COMPLETED"), body);
        int start = body.indexOf("run-");
        return body.substring(start, body.indexOf('"', start));
    }

    @Test
    void runIsVisibleOnlyToItsOwner() throws Exception {
        String run = finishedRun();
        assertEquals(200, server.get("alice", "/api/runs/" + run).statusCode());
        assertEquals(404, server.get("bob", "/api/runs/" + run).statusCode());
        assertEquals(404, server.get("alice", "/api/runs/nope").statusCode());
    }

    @Test
    void turnsReturnTheFullPromptAndReply() throws Exception {
        String run = finishedRun();
        HttpResponse<String> list = server.get("alice", "/api/runs/" + run + "/turns");
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("\"number\":1"), list.body());
        HttpResponse<String> turn = server.get("alice", "/api/runs/" + run + "/turns/1");
        assertEquals(200, turn.statusCode());
        assertTrue(turn.body().contains("Hello"), turn.body());
        assertTrue(turn.body().contains("\"messages\""), turn.body());
        assertEquals(404, server.get("alice", "/api/runs/" + run + "/turns/9").statusCode());
        assertEquals(404, server.get("bob", "/api/runs/" + run + "/turns/1").statusCode());
    }

    @Test
    void eventsStreamDeliversFactsAndChunksToTheOwner() throws Exception {
        HttpRequest request = server.request("alice", "/api/events").GET().build();
        HttpResponse<InputStream> response = server.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        List<String> lines = new ArrayList<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(response.body()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (lines) {
                        lines.add(line);
                    }
                }
            } catch (Exception closed) {
                // 测试结束时连接断开。
            }
        });
        server.post("alice", "/api/sessions/s1/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"hi\"}");
        String text = "";
        for (int i = 0; i < 500 && !text.contains("\"Ended\""); i++) {
            Thread.sleep(10);
            synchronized (lines) {
                text = String.join("\n", lines);
            }
        }
        assertTrue(text.contains("event:PhaseChanged"), text);
        assertTrue(text.contains("event:chunk"), text);
        assertTrue(text.contains("Hel"), text);
        assertTrue(text.contains("id:0.0"), text);
        reader.interrupt();
        response.body().close();
    }

    @Test
    void eventsRequireLogin() throws Exception {
        assertEquals(401, server.get(null, "/api/events").statusCode());
    }

    @Test
    void anotherUserSeesNoEventsFromThisUser() throws Exception {
        String run = finishedRun();
        assertFalse(server.get("bob", "/api/sessions/s1/runs").body().contains(run));
    }
}
