package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

class TraceApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private TestServer server;

    private final ModelClient model = (prompt, chunks) ->
            new ModelOutcome.Replied(List.of(new TextBlock("Hello")), StopReason.END_TURN, "m", null);

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
    void traceHasEventsWithPhasesAndHandlerSegments() throws Exception {
        String run = finishedRun();
        HttpResponse<String> response = server.get("alice", "/api/runs/" + run + "/trace");
        assertEquals(200, response.statusCode());
        JsonNode trace = JSON.readTree(response.body());

        JsonNode events = trace.get("events");
        assertEquals(List.of("UserPrompt", "ContextAssembled", "ModelReplied"),
                events.valueStream().map(event -> event.get("type").asString()).toList());
        assertEquals(List.of("AwaitingContext", "AwaitingModel", "Ended"),
                events.valueStream().map(event -> event.get("phase").asString()).toList());
        assertEquals(1, events.get(1).get("turn").asInt());
        assertTrue(events.get(0).get("at").asString().endsWith("Z"), events.toString());

        JsonNode segments = trace.get("segments");
        assertEquals(List.of("ContextHandler", "ModelHandler"),
                segments.valueStream().map(segment -> segment.get("handler").asString()).toList());
        assertTrue(segments.get(1).has("to"), segments.toString());
    }

    @Test
    void traceIsVisibleOnlyToItsOwner() throws Exception {
        String run = finishedRun();
        assertEquals(404, server.get("bob", "/api/runs/" + run + "/trace").statusCode());
        assertEquals(404, server.get("alice", "/api/runs/nope/trace").statusCode());
    }
}
