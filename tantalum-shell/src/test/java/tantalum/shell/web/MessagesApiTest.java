package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MessagesApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private TestServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    /** 第一轮读文件，拿到结果后回答；提问为 {@code slow} 时等测试放行。 */
    private final ModelClient model = (prompt, chunks) -> {
        ContentBlock last = prompt.messages().getLast().content().getFirst();
        if (last instanceof ToolResultBlock result) {
            return new ModelOutcome.Replied(List.of(new TextBlock("It says: " + result.text())), StopReason.END_TURN, "m", null);
        }
        if (((TextBlock) last).text().equals("slow")) {
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new ModelOutcome.Replied(List.of(new TextBlock("late")), StopReason.END_TURN, "m", null);
        }
        return new ModelOutcome.Replied(List.of(new ToolCallBlock("c1", "read_file", "{\"path\":\"a.txt\"}",
                new Json.Obj(Map.of("path", new Json.Str("a.txt"))))), StopReason.TOOL_USE, "m", null);
    };

    @BeforeEach
    void start() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Files.createDirectory(workspace.resolve("alice"));
        Files.writeString(workspace.resolve("alice/a.txt"), "hello file");
        server = new TestServer(temp.resolve("data"), workspace, model);
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.close();
    }

    private void waitFor(String session, String marker) throws Exception {
        String body = "";
        for (int i = 0; i < 500 && !body.contains(marker); i++) {
            Thread.sleep(10);
            body = server.get("alice", "/api/sessions/" + session + "/messages").body();
        }
        assertTrue(body.contains(marker), body);
    }

    @Test
    void messagesContainTheToolChainWithStatusGroupAndTimes() throws Exception {
        server.post("alice", "/api/sessions/s1/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"read a\"}");
        waitFor("s1", "\"endReason\":\"COMPLETED\"");

        JsonNode body = JSON.readTree(server.get("alice", "/api/sessions/s1/messages").body());
        assertEquals(0, body.get("followUps").asInt());
        // 快照截至的事件序号：这次运行写了 4 个事件（提示、上下文、回复、工具结果、回复），序号从 0 起。
        assertTrue(body.get("seq").asLong() >= 4, body.get("seq").toString());
        JsonNode items = body.get("items");
        List<String> kinds = items.valueStream().map(item -> item.get("kind").asString()).toList();
        assertEquals(List.of("user", "run", "tool", "text"), kinds);

        JsonNode tool = items.get(2);
        assertEquals("DONE", tool.get("status").asString());
        assertEquals("read_file", tool.get("call").get("name").asString());
        assertEquals("toolCall", tool.get("call").get("kind").asString());
        assertEquals("a.txt", tool.get("call").get("arguments").get("path").asString());
        assertTrue(tool.get("result").get("text").asString().contains("hello file"), tool.toString());
        assertTrue(tool.get("group").asString().startsWith("g"), tool.toString());
        assertTrue(tool.has("startedAt") && tool.has("endedAt"), tool.toString());
        assertEquals("s1", tool.get("session").asString());
        assertTrue(tool.get("run").asString().startsWith("run-"));
        assertTrue(items.get(3).get("text").asString().contains("hello file"));
    }

    @Test
    void queuedFollowUpIsAnItemMarkedQueued() throws Exception {
        server.post("alice", "/api/sessions/s2/submissions", "{\"requestId\":\"q1\",\"mode\":\"prompt\",\"text\":\"slow\"}");
        waitFor("s2", "\"phase\":\"AwaitingModel\"");
        server.post("alice", "/api/sessions/s2/submissions", "{\"requestId\":\"q2\",\"mode\":\"follow-up\",\"text\":\"and then\"}");
        waitFor("s2", "\"queued\":true");

        JsonNode body = JSON.readTree(server.get("alice", "/api/sessions/s2/messages").body());
        assertEquals(1, body.get("followUps").asInt());
        JsonNode queued = body.get("items").get(2);
        assertEquals("user", queued.get("kind").asString());
        assertEquals("and then", queued.get("text").asString());
        assertTrue(queued.get("queued").asBoolean());
        assertTrue(!queued.has("run"));
    }

    @Test
    void anotherUserAndUnknownSessionGet404() throws Exception {
        server.post("alice", "/api/sessions/s1/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"read a\"}");
        waitFor("s1", "\"endReason\"");
        HttpResponse<String> other = server.get("bob", "/api/sessions/s1/messages");
        assertEquals(404, other.statusCode());
        assertEquals(404, server.get("alice", "/api/sessions/nope/messages").statusCode());
    }
}
