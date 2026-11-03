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
        int start = body.indexOf("s1_");
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

    /** 提交并等这个会话里第 {@code count} 个运行结束，返回所有运行 ID（旧的在前）。 */
    private List<String> runs(String session, int count) throws Exception {
        server.post("alice", "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"r" + count + "\",\"mode\":\"prompt\",\"text\":\"hi " + count + "\"}");
        JsonNode runs = null;
        for (int i = 0; i < 500; i++) {
            runs = JSON.readTree(server.get("alice", "/api/sessions/" + session + "/runs").body()).get("runs");
            if (runs != null && runs.size() == count && runs.valueStream().allMatch(run -> run.has("endReason"))) {
                break;
            }
            Thread.sleep(10);
        }
        assertEquals(count, runs.size(), runs.toString());
        return runs.valueStream().map(run -> run.get("run").asString()).toList();
    }

    private JsonNode trace(String path) throws Exception {
        HttpResponse<String> response = server.get("alice", path);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    @Test
    void sessionTraceIsOnePageOfRecordsWithoutPromptText() throws Exception {
        runs("s1", 1);
        List<String> both = runs("s1", 2);
        JsonNode page = trace("/api/sessions/s1/trace");

        assertEquals(List.of("seq", "records", "before", "hasMore"), List.copyOf(page.propertyNames()));
        assertEquals(false, page.get("hasMore").asBoolean());
        assertEquals(both.getFirst().substring(both.getFirst().lastIndexOf('_') + 1), page.get("before").asString());
        List<String> kinds = page.get("records").valueStream().map(record -> record.get("kind").asString()).toList();
        assertEquals(List.of("run", "user", "system", "request", "run", "user", "request"), kinds);
        JsonNode first = page.get("records").get(0);
        assertEquals(List.of("kind", "id", "pos", "run", "turn", "at", "end"), List.copyOf(first.propertyNames()).subList(0, 7));
        for (JsonNode record : page.get("records")) {
            assertTrue(!record.has("messages") && !record.has("prompt") && !record.has("schema"), record.toString());
        }
        assertEquals("Hello", page.get("records").get(3).get("text").asString());
    }

    @Test
    void beforeAndRunSelectOlderPagesAndBadParametersAreRejected() throws Exception {
        runs("s1", 1);
        List<String> both = runs("s1", 2);
        String second = both.get(1);
        String secondStart = second.substring(second.lastIndexOf('_') + 1);

        JsonNode older = trace("/api/sessions/s1/trace?before=" + secondStart);
        assertEquals(List.of(both.getFirst()), older.get("records").valueStream().map(record -> record.get("run").asString()).distinct().toList());
        JsonNode through = trace("/api/sessions/s1/trace?run=" + both.getFirst());
        assertEquals(2, through.get("records").valueStream().map(record -> record.get("run").asString()).distinct().count());

        assertEquals(400, server.get("alice", "/api/sessions/s1/trace?before=x").statusCode());
        assertEquals(400, server.get("alice", "/api/sessions/s1/trace?before=1&run=" + second).statusCode());
        assertEquals(400, server.get("alice", "/api/sessions/s1/trace?run=nope").statusCode());
        assertEquals(404, server.get("alice", "/api/sessions/s1/trace?run=other_1").statusCode());
        assertEquals(404, server.get("alice", "/api/sessions/s1/trace?run=s1_9999").statusCode());
    }

    @Test
    void callDetailIsThePromptOfTheTurnAndKnowsThePreviousRequest() throws Exception {
        runs("s1", 1);
        List<String> both = runs("s1", 2);
        JsonNode records = trace("/api/sessions/s1/trace").get("records");
        List<String> calls = records.valueStream().filter(record -> record.get("kind").asString().equals("request"))
                .map(record -> record.get("attempts").get(0).get("call").asString()).toList();

        JsonNode call = trace("/api/sessions/s1/trace/calls/" + calls.get(1));
        JsonNode turn = JSON.readTree(server.get("alice", "/api/runs/" + both.get(1) + "/turns/1").body()).get("prompt");
        assertEquals(turn.get("system"), call.get("system"));
        assertEquals(turn.get("messages"), call.get("messages"));
        assertEquals(turn.get("output"), call.get("output"));
        assertEquals(turn.get("tools"), JSON.valueToTree(call.get("tools").valueStream().map(tool -> tool.get("spec")).toList()));
        assertEquals("context", call.get("segments").get(0).get("source").get("type").asString());
        assertEquals(call.get("system"), call.get("previous").get("system"));
        assertTrue(!trace("/api/sessions/s1/trace/calls/" + calls.get(0)).has("previous"), "the first request has none");
        assertEquals(404, server.get("alice", "/api/sessions/s1/trace/calls/s2_1-0").statusCode());
        assertEquals(404, server.get("alice", "/api/sessions/s1/trace/calls/s1_99-0").statusCode());
        assertEquals(404, server.get("alice", "/api/sessions/s1/trace/calls/garbage").statusCode());
    }

    @Test
    void sessionTraceAndCallsAreVisibleOnlyToTheirOwner() throws Exception {
        runs("s1", 1);
        String call = trace("/api/sessions/s1/trace").get("records").valueStream()
                .filter(record -> record.get("kind").asString().equals("request")).findFirst().orElseThrow()
                .get("attempts").get(0).get("call").asString();

        assertEquals(404, server.get("bob", "/api/sessions/s1/trace").statusCode());
        assertEquals(404, server.get("bob", "/api/sessions/s1/trace?before=5").statusCode());
        assertEquals(404, server.get("bob", "/api/sessions/s1/trace/calls/" + call).statusCode());
        assertEquals(404, server.get("alice", "/api/sessions/nobody/trace").statusCode());
        assertEquals(400, server.get("alice", "/api/sessions/bad.id/trace").statusCode());
    }
}
