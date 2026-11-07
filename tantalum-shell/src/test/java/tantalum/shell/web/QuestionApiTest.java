package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.json.Json;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

/**
 * waiting-state 需求 6：取这个会话正在等回答的问题、回答它。只看得到、只答得了自己的；别人的和已经不在的一样 404。
 * 问题由脚本模型调 {@code ask_user} 提出：不用插件、不用内存里的问题。
 */
class QuestionApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private TestServer server;
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void start() {
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), (prompt, chunks) -> {
            if (calls.getAndIncrement() == 0) {
                Json arguments = new Json.Obj(Map.of("question", new Json.Str("Delete the backups?"),
                        "choices", new Json.Arr(List.of(new Json.Str("delete"), new Json.Str("keep")))));
                return new ModelOutcome.Replied(List.of(new ToolCallBlock("a1", "ask_user", "{}", arguments)), StopReason.TOOL_USE, "m", null);
            }
            return new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);
        });
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private JsonNode open(String user) throws Exception {
        HttpResponse<String> response = server.get(user, "/api/sessions/s1/questions");
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body()).get("questions");
    }

    private JsonNode awaitQuestion(String user) throws Exception {
        JsonNode questions = open(user);
        for (int i = 0; i < 400 && questions.isEmpty(); i++) {
            Thread.sleep(10);
            questions = open(user);
        }
        return questions;
    }

    @Test
    void onlyTheAskedUserSeesAndAnswersTheQuestion() throws Exception {
        server.get("bob", "/api/sessions");
        assertEquals(200, server.post("alice", "/api/sessions/s1/submissions",
                "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"clean up\"}").statusCode());
        JsonNode questions = awaitQuestion("alice");
        assertEquals(1, questions.size(), questions.toString());
        JsonNode question = questions.get(0);
        assertEquals("ask-a1", question.get("id").asString());
        // ask_user 的问题是模型写的原文：字符串，不是按语言的对象
        assertEquals("Delete the backups?", question.get("title").asString());
        assertEquals("delete", question.get("choices").get(0).get("id").asString());
        assertTrue(question.get("freeText").asBoolean());
        assertTrue(!question.has("deadline") || question.get("deadline").isNull(), "no deadline");
        assertEquals("ask_user", question.get("askedBy").asString());
        assertTrue(open("bob").isEmpty(), "bob does not see alice's question");

        assertEquals(404, server.post("bob", "/api/sessions/s1/questions/ask-a1", "{\"choice\":\"keep\"}").statusCode());
        assertEquals(400, server.post("alice", "/api/sessions/s1/questions/ask-a1", "{\"choice\":\"maybe\"}").statusCode());
        assertEquals(400, server.post("alice", "/api/sessions/s1/questions/ask-a1", "{}").statusCode());
        assertEquals(400, server.post("alice", "/api/sessions/s1/questions/ask-a1",
                "{\"text\":\"" + "x".repeat(4001) + "\"}").statusCode());
        HttpResponse<String> gone = server.post("alice", "/api/sessions/s1/questions/ask-other", "{\"choice\":\"keep\"}");
        assertEquals(404, gone.statusCode());
        assertTrue(gone.body().contains("\"code\":\"question/gone\""), gone.body());

        HttpResponse<String> answered = server.post("alice", "/api/sessions/s1/questions/ask-a1", "{\"choice\":\"keep\",\"text\":\"归档到 old/\"}");
        assertEquals(200, answered.statusCode(), answered.body());
        assertTrue(answered.body().contains("\"text\":\"归档到 old/\""), answered.body());

        // 回答落盘后页面上就没有问题了，再答一次是“已经不在”
        assertTrue(open("alice").isEmpty());
        HttpResponse<String> again = server.post("alice", "/api/sessions/s1/questions/ask-a1", "{\"choice\":\"keep\"}");
        assertEquals(404, again.statusCode());
        assertTrue(again.body().contains("\"code\":\"question/gone\""), again.body());

        // 运行继续：模型收到工具结果后回答 ok
        JsonNode runs = null;
        for (int i = 0; i < 400; i++) {
            runs = JSON.readTree(server.get("alice", "/api/sessions/s1/runs").body()).get("runs");
            if (runs.size() == 1 && runs.get(0).has("endReason")) {
                break;
            }
            Thread.sleep(10);
        }
        assertEquals("COMPLETED", runs.get(0).get("endReason").asString(), runs.toString());
        assertEquals(2, calls.get());

        // 需求 7.1、7.4：对话里留下一条已回答的问题条目；轨迹页有一条问答记录
        String messages = server.get("alice", "/api/sessions/s1/messages").body();
        assertTrue(messages.contains("\"kind\":\"question\""), messages);
        assertTrue(messages.contains("\"status\":\"ANSWERED\""), messages);
        assertTrue(messages.contains("\"choice\":\"keep\""), messages);
        assertTrue(messages.contains("\"askedBy\":\"ask_user\""), messages);
        String trace = server.get("alice", "/api/sessions/s1/trace").body();
        assertTrue(trace.contains("\"kind\":\"question\""), trace);
        assertTrue(trace.contains("\"status\":\"ANSWERED\""), trace);
    }
}
