package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

/** memory-capture 需求 1、3、4：聊完一轮自动记下笔记；只给本人；能改能删；开关；只读查看能看不能改。 */
class MemoryNotesApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private TestServer server;
    private final AtomicInteger notePrompts = new AtomicInteger();

    private final ModelClient model = (prompt, chunks) -> {
        if (prompt.system().startsWith("You keep notes for a user's long-term memory")) {
            notePrompts.incrementAndGet();
            return new ModelOutcome.Replied(List.of(new TextBlock("- [note] 用户想把记忆往前提 «把记忆往前提»\n- [task] 下一步：自动记下 «把记忆往前提»")), StopReason.END_TURN, "m", null);
        }
        return new ModelOutcome.Replied(List.of(new TextBlock("好的")), StopReason.END_TURN, "m", null);
    };

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("workspace")), model,
                "--tantalum.memory-capture.enabled=true");
        if (server.users().status("root").isEmpty()) {
            server.users().add("root", TestServer.PASSWORD, true);
        }
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private JsonNode get(String user, String path) throws Exception {
        HttpResponse<String> response = server.get(user, path);
        assertEquals(200, response.statusCode(), path + " " + response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> send(String user, String method, String path, String body) throws Exception {
        return server.http.send(server.request(user, path).header("content-type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode waitForNotes(String user, int sections) throws Exception {
        JsonNode days = null;
        for (int i = 0; i < 500; i++) {
            days = get(user, "/api/plugin-api/memory/notes").get("days");
            if (days.size() > 0 && days.get(0).get("notes").get(0).get("sections").asInt() >= sections) {
                return days;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no note yet: " + days);
    }

    @Test
    void aFinishedTurnIsNotedAndOnlyItsOwnerCanChangeIt() throws Exception {
        server.post("alice", "/api/sessions/s1/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"把记忆往前提\"}");
        JsonNode days = waitForNotes("alice", 1);
        String day = LocalDate.now(ZoneId.systemDefault()).toString();
        assertEquals(day, days.get(0).get("day").asString());
        JsonNode listed = days.get(0).get("notes").get(0);
        assertEquals("s1", listed.get("session").asString());
        assertTrue(listed.get("preview").asString().contains("自动记下"));

        JsonNode note = get("alice", "/api/plugin-api/memory/notes/" + day + "/s1");
        assertTrue(note.get("sections").get(0).get("run").asString().startsWith("s1_"));
        assertTrue(note.get("text").asString().contains("用户想把记忆往前提"));

        assertEquals(0, get("bob", "/api/plugin-api/memory/notes").get("days").size(), "bob does not see alice's notes");
        // 记忆是个人的（Yi 2026-11-15）：管理员只读查看时连笔记也看不到
        assertEquals(403, server.get("root", "/api/plugin-api/memory/notes?viewAs=alice").statusCode(), "the admin cannot read notes while viewing");
        assertTrue(send("root", "DELETE", "/api/plugin-api/memory/notes/" + day + "/s1?viewAs=alice", null).statusCode() >= 400, "but not delete");

        String edited = note.get("text").asString().replace("用户想把记忆往前提", "用户决定先做记忆");
        assertEquals(200, send("alice", "PUT", "/api/plugin-api/memory/notes/" + day + "/s1", JSON.writeValueAsString(java.util.Map.of("text", edited))).statusCode());
        assertTrue(get("alice", "/api/plugin-api/memory/notes/" + day + "/s1").get("text").asString().contains("用户决定先做记忆"));
        assertEquals(200, send("alice", "DELETE", "/api/plugin-api/memory/notes/" + day + "/s1", null).statusCode());
        assertEquals(0, get("alice", "/api/plugin-api/memory/notes").get("days").size());
        assertEquals(404, server.get("alice", "/api/plugin-api/memory/notes/" + day + "/s1").statusCode());
        assertEquals(400, server.get("alice", "/api/plugin-api/memory/notes/yesterday/s1").statusCode());
    }

    @Test
    void turningCaptureOffStopsNewNotes() throws Exception {
        JsonNode capture = get("alice", "/api/plugin-api/memory/capture");
        assertTrue(capture.get("enabled").asBoolean());
        assertTrue(capture.get("server").asBoolean());
        // 开关是记忆插件在插件页上的参数（memory-plugin 需求 2.3）
        assertEquals(200, send("alice", "PUT", "/api/plugins/plugin/memory/params", "{\"values\":{\"capture\":false}}").statusCode());
        assertFalse(get("alice", "/api/plugin-api/memory/capture").get("enabled").asBoolean());
        assertEquals(404, send("alice", "PUT", "/api/plugin-api/memory/capture", "{\"enabled\":true}").statusCode(), "the switch moved");
        assertFalse(get("alice", "/api/plugin-api/memory/capture").get("enabled").asBoolean());

        server.post("alice", "/api/sessions/s2/submissions", "{\"requestId\":\"r2\",\"mode\":\"prompt\",\"text\":\"不要记\"}");
        String body = "";
        for (int i = 0; i < 500 && !body.contains("COMPLETED"); i++) {
            Thread.sleep(10);
            body = server.get("alice", "/api/sessions/s2/runs").body();
        }
        Thread.sleep(300);
        assertEquals(0, notePrompts.get(), "no note was even attempted");
        assertEquals(0, get("alice", "/api/plugin-api/memory/notes").get("days").size());
    }
}
