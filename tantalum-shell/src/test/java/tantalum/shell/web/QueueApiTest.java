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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
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

/**
 * input-area 需求 1.3、1.5、1.6、1.11：改、移除排队的提问，或把它转成插话。只动得了自己会话里还在排队的；
 * 不在了是 404 {@code queue/gone}。模型的第一次调用一直不返回，队列才留得住。
 */
class QueueApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private TestServer server;
    private final CountDownLatch hold = new CountDownLatch(1);
    private final AtomicInteger calls = new AtomicInteger();
    private int requests;

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
        server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("workspace")), held);
        submit("prompt", "first");
        for (int i = 0; i < 500 && calls.get() < 1; i++) {
            Thread.sleep(10);
        }
        submit("follow-up", "a");
        submit("follow-up", "b");
        await(items -> items.stream().filter(item -> item.path("queued").asBoolean()).count() == 2);
    }

    @AfterEach
    void stop() {
        hold.countDown();
        server.close();
    }

    private void submit(String mode, String text) throws Exception {
        HttpResponse<String> response = server.post("alice", "/api/sessions/s1/submissions",
                "{\"requestId\":\"r" + requests++ + "\",\"mode\":\"" + mode + "\",\"text\":\"" + text + "\"}");
        assertEquals(200, response.statusCode(), response.body());
    }

    private List<JsonNode> users() throws Exception {
        List<JsonNode> out = new ArrayList<>();
        JSON.readTree(server.get("alice", "/api/sessions/s1/messages").body()).get("items").forEach(item -> {
            if (item.path("kind").asString().equals("user")) {
                out.add(item);
            }
        });
        return out;
    }

    private List<JsonNode> await(Predicate<List<JsonNode>> done) throws Exception {
        List<JsonNode> items = users();
        for (int i = 0; i < 500 && !done.test(items); i++) {
            Thread.sleep(10);
            items = users();
        }
        assertTrue(done.test(items), items.toString());
        return items;
    }

    private JsonNode byText(String text) throws Exception {
        return users().stream().filter(item -> item.path("text").asString().equals(text)).findFirst().orElseThrow();
    }

    private HttpResponse<String> change(String user, String item, String body) throws Exception {
        return server.post(user, "/api/sessions/s1/queue/" + item, body);
    }

    private static String body(String request, String action, String text) {
        return "{\"requestId\":\"" + request + "\",\"action\":\"" + action + "\"" + (text == null ? "" : ",\"text\":\"" + text + "\"") + "}";
    }

    @Test
    void editRemoveAndSteerChangeTheQueue() throws Exception {
        String a = byText("a").get("id").asString();
        String b = byText("b").get("id").asString();

        HttpResponse<String> edited = change("alice", b, body("q1", "edit", "b2"));
        assertEquals(200, edited.statusCode(), edited.body());
        assertTrue(edited.body().contains("submissionId"), edited.body());
        await(items -> items.stream().anyMatch(item -> item.path("text").asString().equals("b2") && item.path("queued").asBoolean()));

        assertEquals(200, change("alice", a, body("q2", "remove", null)).statusCode());
        List<JsonNode> afterRemove = await(items -> items.stream().anyMatch(item -> item.path("removed").asBoolean()));
        JsonNode removed = afterRemove.stream().filter(item -> item.path("id").asString().equals(a)).findFirst().orElseThrow();
        assertTrue(removed.path("removed").asBoolean());
        assertFalse(removed.path("queued").asBoolean());
        assertTrue(server.get("alice", "/api/sessions/s1/messages").body().contains("\"followUps\":1"));

        // 移除了的不能再动
        HttpResponse<String> again = change("alice", a, body("q3", "remove", null));
        assertEquals(404, again.statusCode(), again.body());
        assertTrue(again.body().contains("queue/gone"), again.body());

        assertEquals(200, change("alice", b, body("q4", "steer", null)).statusCode());
        List<JsonNode> afterSteer = await(items -> items.stream().noneMatch(item -> item.path("queued").asBoolean()));
        JsonNode steered = afterSteer.stream().filter(item -> item.path("id").asString().equals(b)).findFirst().orElseThrow();
        assertEquals(byText("first").get("run").asString(), steered.get("run").asString(), "joined the running run");
        assertTrue(server.get("alice", "/api/sessions/s1/messages").body().contains("\"followUps\":0"));
    }

    @Test
    void badRequestsAndOtherPeoplesSessionsAreRefused() throws Exception {
        String a = byText("a").get("id").asString();
        assertEquals(400, change("alice", a, body("q1", "edit", null)).statusCode(), "edit needs text");
        assertEquals(400, change("alice", a, body("q1", "edit", "  ")).statusCode());
        assertEquals(400, change("alice", a, body("q1", "move", null)).statusCode());
        assertEquals(400, change("alice", a, "{\"action\":\"remove\"}").statusCode(), "requestId is required");

        HttpResponse<String> notQueued = change("alice", byText("first").get("id").asString(), body("q2", "remove", null));
        assertEquals(404, notQueued.statusCode());
        assertTrue(notQueued.body().contains("queue/gone"), notQueued.body());
        assertEquals(404, change("alice", "nope", body("q3", "remove", null)).statusCode());

        HttpResponse<String> stranger = change("bob", a, body("q4", "remove", null));
        assertEquals(404, stranger.statusCode());
        assertTrue(stranger.body().contains("session/not-found"), stranger.body());
        assertEquals(2, users().stream().filter(item -> item.path("queued").asBoolean()).count());
    }
}
