package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * input-area 需求 9.1、9.4、9.7：上下文占用接口。{@code used} 是模型实测的加新增的估算，三项摊分之和等于它；
 * 还没调过模型时什么都不回（空对象）；只回数字；别人的会话 404。
 */
class ContextEndpointTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SECRET_TEXT = "pineapple-4417";

    @TempDir
    Path temp;

    private static ModelClient model(CountDownLatch hold, TokenUsage usage) {
        return (prompt, chunks) -> {
            try {
                hold.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new ModelOutcome.Interrupted("");
            }
            return new ModelOutcome.Replied(List.of(new TextBlock("reply about " + SECRET_TEXT)), StopReason.END_TURN, "m", usage);
        };
    }

    private static JsonNode context(TestServer server, String user, String session) throws Exception {
        var response = server.get(user, "/api/sessions/" + session + "/context");
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static void prompt(TestServer server, String session, String id) throws Exception {
        assertEquals(200, server.post("alice", "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"" + id + "\",\"mode\":\"prompt\",\"text\":\"tell me about " + SECRET_TEXT + "\"}").statusCode());
    }

    private static void settle(TestServer server, String session, int count) throws Exception {
        for (int i = 0; i < 2000; i++) {
            JsonNode runs = JSON.readTree(server.get("alice", "/api/sessions/" + session + "/runs").body()).get("runs");
            if (runs != null && runs.size() == count && runs.get(count - 1).has("endReason")) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("runs did not settle");
    }

    @Test
    void reportsWhatTheModelMeasuredSplitIntoThreeParts() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        try (TestServer server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("ws")),
                model(hold, new TokenUsage(4000, 200, 1000, 300)), "--tantalum.claude.context-window=100000", "--tantalum.claude.max-tokens=8000",
                "--tantalum.compaction.reserve-tokens=8000")) {
            assertEquals(404, server.get("alice", "/api/sessions/s1/context").statusCode(), "no such session yet");

            prompt(server, "s1", "r1");
            Thread.sleep(200);
            assertEquals("{}", context(server, "alice", "s1").toString(), "the model has not answered yet");

            hold.countDown();
            settle(server, "s1", 1);
            var response = server.get("alice", "/api/sessions/s1/context");
            JsonNode body = JSON.readTree(response.body());
            long used = body.get("used").asLong();
            // 实测 4000 + 1000 + 300 读入、200 输出；锚点之后新增的消息另外估算
            assertTrue(used >= 5500 && used < 5600, body.toString());
            assertEquals(100000, body.get("window").asLong());
            assertEquals(92000, body.get("limit").asLong(), "the user's compaction point: full, so window minus reserve");
            JsonNode parts = body.get("parts");
            assertEquals(List.of("system", "tools", "messages"), List.copyOf(parts.propertyNames()));
            assertEquals(used, parts.get("system").asLong() + parts.get("tools").asLong() + parts.get("messages").asLong());
            assertTrue(parts.get("system").asLong() > 0 && parts.get("tools").asLong() > 0 && parts.get("messages").asLong() > 0, parts.toString());
            assertTrue(parts.get("tools").asLong() > parts.get("messages").asLong(), "tool definitions outweigh two short messages: " + parts);
            assertFalse(response.body().contains(SECRET_TEXT), "numbers only");
            assertEquals(4, body.size());

            assertEquals(404, server.get("bob", "/api/sessions/s1/context").statusCode());
            assertEquals(404, server.get("alice", "/api/sessions/nope/context").statusCode());
            assertEquals(400, server.get("alice", "/api/sessions/bad%20id/context").statusCode());
            assertEquals(401, server.get(null, "/api/sessions/s1/context").statusCode());
        }
    }

    /** 模型不报用量：没有实测可摊，不显示。窗口未知：有用量但没有窗口和压缩点。 */
    @Test
    void withoutMeasuredUsageOrWindowItSaysLess() throws Exception {
        CountDownLatch open = new CountDownLatch(0);
        try (TestServer server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("ws")), model(open, null))) {
            prompt(server, "s1", "r1");
            settle(server, "s1", 1);
            assertEquals("{}", context(server, "alice", "s1").toString());
        }
        try (TestServer server = new TestServer(temp.resolve("data2"), Files.createDirectory(temp.resolve("ws2")),
                model(open, new TokenUsage(900, 100, 0, 0)), "--tantalum.claude.context-window=0")) {
            prompt(server, "s1", "r1");
            settle(server, "s1", 1);
            JsonNode body = context(server, "alice", "s1");
            assertTrue(body.get("used").asLong() >= 1000, body.toString());
            assertFalse(body.has("window"));
            assertFalse(body.has("limit"));
            assertTrue(body.has("parts"));
        }
    }
}
