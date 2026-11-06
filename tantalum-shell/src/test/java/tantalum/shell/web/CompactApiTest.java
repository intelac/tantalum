package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/** context-compaction 需求 5.1：手动压缩接口的每个状态码。 */
class CompactApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 窗口 6000、预留 500：阈值 5500。正常对话远在阈值之下，只有手动压缩会触发摘要调用。 */
    // 估算系数按旧的 3 算，否则 system 和工具定义就占满了，摘要调用放不下（compaction-threshold 改了默认系数）；
    // local-workspace 换成本机工作区的 11 个工具和 read_attachment，工具定义变长，窗口从 4000 加到 6000

    private static final String[] SETTINGS = {"--tantalum.claude.context-window=6000", "--tantalum.claude.max-tokens=500",
        "--tantalum.claude.bytes-per-token=3",
        "--tantalum.compaction.reserve-tokens=500", "--tantalum.compaction.keep-recent-tokens=600"};

    @TempDir
    Path temp;

    /** 摘要调用回一段摘要，普通调用回一句话；{@code hold} 不为空时普通调用先等它放行。 */
    private static ModelClient model(CountDownLatch hold) {
        return (prompt, chunks) -> {
            if (!prompt.output().toolsAllowed()) {
                String last = ((TextBlock) prompt.messages().get(prompt.messages().size() - 2).content().getFirst()).text();
                return new ModelOutcome.Replied(List.of(new TextBlock("## Goal\nSummary after: " + last)), StopReason.END_TURN, "m",
                        new TokenUsage(30, 10, 0, 0));
            }
            if (hold != null) {
                try {
                    hold.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new ModelOutcome.Interrupted("");
                }
            }
            String text = "reply " + ("x ").repeat(700);
            chunks.onText(text);
            return new ModelOutcome.Replied(List.of(new TextBlock(text)), StopReason.END_TURN, "m", new TokenUsage(200, 20, 0, 0));
        };
    }

    /** 每条消息约 1400 字节、470 token：保留预算 600 只够留最后一条，手动压缩才有头部可压。 */
    private static void prompt(TestServer server, String user, String session, String id) throws Exception {
        prompt(server, user, session, id, 700);
    }

    private static void prompt(TestServer server, String user, String session, String id, int repeat) throws Exception {
        assertEquals(200, server.post(user, "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"" + id + "\",\"mode\":\"prompt\",\"text\":\"question " + id + " " + ("y ").repeat(repeat) + "\"}").statusCode());
    }

    private static JsonNode waitForRuns(TestServer server, String user, String session, int count) throws Exception {
        JsonNode runs = null;
        for (int i = 0; i < 1000; i++) {
            runs = JSON.readTree(server.get(user, "/api/sessions/" + session + "/runs").body()).get("runs");
            if (runs != null && runs.size() == count && runs.get(runs.size() - 1).has("endReason")) {
                return runs.get(runs.size() - 1);
            }
            Thread.sleep(5);
        }
        throw new AssertionError("runs did not settle: " + runs);
    }

    @Test
    void compactsAnIdleSessionAndShowsTheMarkThenRejectsRepeatsAndStrangers() throws Exception {
        try (TestServer server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("ws")), model(null), SETTINGS)) {
            prompt(server, "alice", "s1", "r1");
            waitForRuns(server, "alice", "s1", 1);
            prompt(server, "alice", "s1", "r2");
            waitForRuns(server, "alice", "s1", 2);

            HttpResponse<String> accepted = server.post("alice", "/api/sessions/s1/compact", "{\"instructions\":\"keep the questions\"}");
            assertEquals(200, accepted.statusCode(), accepted.body());
            assertTrue(accepted.body().contains("submissionId"));
            JsonNode run = waitForRuns(server, "alice", "s1", 3);
            assertEquals("COMPLETED", run.get("endReason").asString(), run.toString());
            assertEquals("compacted", run.get("detail").asString());

            JsonNode items = JSON.readTree(server.get("alice", "/api/sessions/s1/messages").body()).get("items");
            JsonNode mark = null;
            for (JsonNode item : items) {
                if (item.get("kind").asString().equals("compaction")) {
                    mark = item;
                }
            }
            assertTrue(mark != null, items.toString());
            assertEquals("MANUAL", mark.get("trigger").asString());
            assertTrue(mark.get("summary").asString().startsWith("## Goal"), mark.toString());

            // 很短的会话：全部都在保留预算内，没有可压缩的头部 → 409。
            prompt(server, "alice", "s2", "t1", 3);
            waitForRuns(server, "alice", "s2", 1);
            assertEquals(409, server.post("alice", "/api/sessions/s2/compact", "").statusCode());
            // 别人的会话和不存在的会话都是 404。
            assertEquals(404, server.post("bob", "/api/sessions/s1/compact", "{}").statusCode());
            assertEquals(404, server.post("alice", "/api/sessions/nope/compact", "{}").statusCode());
            // 跨站写操作被拒。
            HttpRequest cross = server.request("alice", "/api/sessions/s1/compact").header("Origin", "https://evil.example")
                    .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            assertEquals(403, server.http.send(cross, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(400, server.post("alice", "/api/sessions/s1/compact", "{not json").statusCode());
        }
    }

    @Test
    void runningSessionIsRejectedWithConflict() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        try (TestServer server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("ws")), model(hold), SETTINGS)) {
            prompt(server, "alice", "s1", "r1");
            Thread.sleep(200);   // 运行已开始，模型调用被卡住
            assertEquals(409, server.post("alice", "/api/sessions/s1/compact", "{}").statusCode());
            hold.countDown();
            waitForRuns(server, "alice", "s1", 1);
        }
    }

    @Test
    void sessionWithoutCompactionSettingsIsRejected() throws Exception {
        try (TestServer server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("ws")), model(null),
                "--tantalum.claude.context-window=0")) {
            prompt(server, "alice", "s1", "r1");
            waitForRuns(server, "alice", "s1", 1);
            prompt(server, "alice", "s1", "r2");
            waitForRuns(server, "alice", "s1", 2);
            assertEquals(409, server.post("alice", "/api/sessions/s1/compact", "{}").statusCode());
        }
    }

    @Test
    void sameRequestIdIsADuplicateNotASecondCompaction() throws Exception {
        try (TestServer server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("ws")), model(null), SETTINGS)) {
            prompt(server, "alice", "s1", "r1");
            waitForRuns(server, "alice", "s1", 1);
            prompt(server, "alice", "s1", "r2");
            waitForRuns(server, "alice", "s1", 2);
            HttpResponse<String> first = server.post("alice", "/api/sessions/s1/compact", "{\"requestId\":\"c1\"}");
            HttpResponse<String> again = server.post("alice", "/api/sessions/s1/compact", "{\"requestId\":\"c1\"}");
            assertEquals(200, first.statusCode());
            // 第二次要么是去重（200 duplicate），要么因为运行已开始而 409；两者都不会开第二个压缩。
            assertTrue(again.statusCode() == 200 && again.body().contains("\"duplicate\":true") || again.statusCode() == 409, again.body());
            waitForRuns(server, "alice", "s1", 3);
        }
    }
}
