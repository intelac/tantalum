package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import tantalum.value.model.TokenUsage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** run-tree 需求 1、4：一次运行的调用树、跨会话的运行列表；只看自己的，管理员只读查看照旧。 */
class RunsApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private TestServer server;

    private final ModelClient model = (prompt, chunks) ->
            new ModelOutcome.Replied(List.of(new TextBlock("Hello secret answer")), StopReason.END_TURN, "m", new TokenUsage(120, 7, 0, 0));

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("workspace")), model);
        if (server.users().status("root").isEmpty()) {
            server.users().add("root", TestServer.PASSWORD, true);
        }
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private void prompt(String session, String request, String text) throws Exception {
        server.post("alice", "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"" + request + "\",\"delivery\":\"queue\",\"text\":\"" + text + "\"}");
    }

    /** 等运行表里有 {@code count} 个结束了的运行。 */
    private JsonNode finishedRuns(String user, String query, int count) throws Exception {
        JsonNode runs = null;
        for (int i = 0; i < 500; i++) {
            HttpResponse<String> response = server.get(user, "/api/runs" + query);
            assertEquals(200, response.statusCode(), response.body());
            runs = JSON.readTree(response.body()).get("runs");
            if (runs.size() == count && runs.valueStream().allMatch(run -> run.has("endReason"))) {
                return runs;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("runs never finished: " + runs);
    }

    private static boolean mentions(JsonNode node, String text) {
        return node.toString().contains(text);
    }

    @Test
    void runsAcrossSessionsAndTheTreeOfOne() throws Exception {
        prompt("s1", "r1", "first question");
        finishedRuns("alice", "", 1);
        prompt("s2", "r2", "second question");
        JsonNode runs = finishedRuns("alice", "", 2);

        JsonNode newest = runs.get(0);
        assertEquals("s2", newest.get("session").asString());
        assertEquals("second question", newest.get("prompt").asString());
        assertEquals("COMPLETED", newest.get("endReason").asString());
        assertEquals(120, newest.get("tally").get("tokens").get("input").asInt());
        assertEquals(List.of("m"), newest.get("tally").get("models").valueStream().map(JsonNode::asString).toList());
        assertTrue(newest.get("next") == null || newest.get("next").isNull());
        assertFalse(mentions(runs, "secret answer"), "the list has no reply text");

        JsonNode one = finishedRuns("alice", "?session=s1", 1);
        String run = one.get(0).get("run").asString();
        HttpResponse<String> response = server.get("alice", "/api/sessions/s1/runs/" + run + "/tree");
        assertEquals(200, response.statusCode(), response.body());
        JsonNode root = JSON.readTree(response.body()).get("root");
        assertEquals("run", root.get("kind").asString());
        assertEquals("ok", root.get("state").asString());
        assertEquals("PROMPT", root.get("name").asString());
        assertTrue(root.get("tally").has("tokens"));
        JsonNode step = root.get("children").valueStream().filter(node -> node.get("kind").asString().equals("step")).findFirst().orElseThrow();
        JsonNode request = step.get("children").valueStream().filter(node -> node.get("kind").asString().equals("request")).findFirst().orElseThrow();
        assertEquals("attempt", request.get("children").get(0).get("kind").asString());
        assertTrue(request.has("record"), "the request names its trace record");
        assertFalse(mentions(root, "secret answer") || mentions(root, "first question"), "the tree has no text: " + root);

        JsonNode page = JSON.readTree(server.get("alice", "/api/runs?limit=1").body());
        assertEquals(1, page.get("runs").size());
        String next = page.get("next").asString();
        JsonNode second = JSON.readTree(server.get("alice", "/api/runs?limit=1&cursor=" + next).body());
        assertEquals("s1", second.get("runs").get(0).get("session").asString());
        assertFalse(second.has("next") && !second.get("next").isNull(), "no next page");
        assertEquals(2, JSON.readTree(server.get("alice", "/api/runs?end=completed&sort=tokens").body()).get("runs").size());
        assertEquals(0, JSON.readTree(server.get("alice", "/api/runs?end=failed,running").body()).get("runs").size());
        assertEquals(0, JSON.readTree(server.get("alice", "/api/runs?toolErrors=true").body()).get("runs").size());

        JsonNode summary = JSON.readTree(server.get("alice", "/api/runs/summary?tz=Asia/Tokyo").body());
        assertEquals(2, summary.get("runs").asInt());
        assertEquals(2, summary.get("ends").get("COMPLETED").asInt());
        assertEquals(2 * 127, summary.get("tokens").asInt());
        assertEquals(List.of("m"), summary.get("models").valueStream().map(JsonNode::asString).toList());
        assertTrue(summary.get("days").get(0).get("day").asString().matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    void othersSeeNothingAndTheAdminViewsReadOnly() throws Exception {
        prompt("s1", "r1", "mine");
        String run = finishedRuns("alice", "", 1).get(0).get("run").asString();

        assertEquals(0, JSON.readTree(server.get("bob", "/api/runs").body()).get("runs").size());
        assertEquals(404, server.get("bob", "/api/sessions/s1/runs/" + run + "/tree").statusCode());
        assertEquals(1, JSON.readTree(server.get("root", "/api/runs?viewAs=alice").body()).get("runs").size());
        assertEquals(200, server.get("root", "/api/sessions/s1/runs/" + run + "/tree?viewAs=alice").statusCode());
        assertEquals(1, JSON.readTree(server.get("root", "/api/runs/summary?viewAs=alice").body()).get("runs").asInt());
        assertEquals(0, JSON.readTree(server.get("bob", "/api/runs/summary").body()).get("runs").asInt());
    }

    @Test
    void badParametersAre400AndUnknownRunsAre404() throws Exception {
        prompt("s1", "r1", "x");
        finishedRuns("alice", "", 1);
        for (String query : List.of("?sort=slowest", "?limit=0", "?limit=201", "?limit=x", "?end=nope", "?from=yesterday",
                "?toolErrors=yes", "?cursor=bad!", "?session=..%2Fx")) {
            HttpResponse<String> response = server.get("alice", "/api/runs" + query);
            assertEquals(400, response.statusCode(), query + " " + response.body());
            assertTrue(response.body().contains("request/invalid"), response.body());
        }
        assertEquals(400, server.get("alice", "/api/runs/summary?tz=Mars/Olympus").statusCode());
        assertEquals(400, server.get("alice", "/api/runs/summary?end=nope").statusCode());
        assertEquals(400, server.get("alice", "/api/sessions/s1/runs/nope/tree").statusCode());
        assertEquals(404, server.get("alice", "/api/sessions/s1/runs/other_1/tree").statusCode());
        assertEquals(404, server.get("alice", "/api/sessions/s1/runs/s1_9999/tree").statusCode());
        assertEquals(404, server.get("alice", "/api/sessions/s9/runs/s9_1/tree").statusCode());
    }
}
