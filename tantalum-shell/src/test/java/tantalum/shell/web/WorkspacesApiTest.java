package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

/** workspaces 任务 9（设计第 6 节、需求 1、5、6）：工作区接口、提问带工作区、会话改名和归档。session-find-pin：置顶、按内容搜。 */
class WorkspacesApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    /** 打开时模型立刻回答；关上时模型一直等，运行停在等模型。 */
    private volatile CountDownLatch gate = new CountDownLatch(0);
    private TestServer server;

    @BeforeEach
    void start() {
        ModelClient model = (prompt, chunks) -> {
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
            return new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);
        };
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model);
    }

    @AfterEach
    void stop() {
        gate.countDown();
        server.close();
    }

    private HttpResponse<String> send(String user, String method, String path, String body) throws Exception {
        HttpRequest request = server.request(user, path).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        return server.http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String code(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("code").asString();
    }

    private HttpResponse<String> ask(String user, String session, String text, String project) throws Exception {
        String extra = project == null ? "" : ",\"project\":\"" + project + "\"";
        return server.post(user, "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"" + session + "-" + text.hashCode() + "\",\"delivery\":\"queue\",\"text\":\"" + text + "\"" + extra + "}");
    }

    private JsonNode session(String user, String session) throws Exception {
        for (JsonNode item : JSON.readTree(server.get(user, "/api/sessions").body()).get("sessions")) {
            if (item.get("id").asString().equals(session)) {
                return item;
            }
        }
        return null;
    }

    /** 列表来自索引，索引稍后才写：等到条件成立，最多 5 秒。 */
    private JsonNode waitFor(String user, String session, Predicate<JsonNode> condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        JsonNode found = session(user, session);
        while ((found == null || !condition.test(found)) && System.nanoTime() < until) {
            Thread.sleep(20);
            found = session(user, session);
        }
        return found;
    }

    private JsonNode idle(String user, String session) throws Exception {
        return waitFor(user, session, item -> item.get("runStatus").asString().equals("idle"));
    }

    @Test
    void projectsAreListedAddedRenamedAndDeletedPerPerson() throws Exception {
        HttpResponse<String> added = server.post("alice", "/api/projects", "{\"path\":\"C:\\\\APPL\\\\Quattro\"}");
        assertEquals(201, added.statusCode(), added.body());
        JsonNode project = JSON.readTree(added.body());
        assertEquals("Quattro", project.get("title").asString());
        String id = project.get("id").asString();

        assertEquals("project/path-taken", code(server.post("alice", "/api/projects", "{\"path\":\"c:/appl/quattro/\"}")));
        assertEquals("project/path-invalid", code(server.post("alice", "/api/projects", "{\"path\":\"quattro\"}")));
        assertEquals("project/title-taken", code(server.post("alice", "/api/projects", "{\"path\":\"/x/Quattro\"}")));
        HttpResponse<String> tooLong = server.post("alice", "/api/projects", "{\"path\":\"/x/y\",\"title\":\"" + "n".repeat(81) + "\"}");
        assertEquals(400, tooLong.statusCode());
        assertEquals(80, JSON.readTree(tooLong.body()).path("params").path("max").asInt(), tooLong.body());

        assertEquals(1, JSON.readTree(server.get("alice", "/api/projects").body()).get("projects").size());
        assertEquals(0, JSON.readTree(server.get("bob", "/api/projects").body()).get("projects").size());
        assertEquals(404, send("bob", "PATCH", "/api/projects/" + id, "{\"title\":\"mine\"}").statusCode());
        assertEquals(404, send("bob", "DELETE", "/api/projects/" + id, null).statusCode());

        HttpResponse<String> renamed = send("alice", "PATCH", "/api/projects/" + id, "{\"title\":\"Quattro 主线\"}");
        assertEquals("Quattro 主线", JSON.readTree(renamed.body()).get("title").asString());
        assertEquals(204, send("alice", "DELETE", "/api/projects/" + id, null).statusCode());
        assertEquals("project/not-found", code(send("alice", "DELETE", "/api/projects/" + id, null)));
    }

    @Test
    void aPromptWithAProjectGivesTheSessionItsDirectory() throws Exception {
        String id = JSON.readTree(server.post("alice", "/api/projects", "{\"path\":\"/home/alice/app\"}").body()).get("id").asString();
        String bobs = JSON.readTree(server.post("bob", "/api/projects", "{\"path\":\"/home/bob/app\"}").body()).get("id").asString();

        assertEquals("project/not-found", code(ask("alice", "s1", "hi", bobs)), "another person's project is not found");
        assertEquals(200, ask("alice", "s1", "hi", id).statusCode());
        assertEquals("/home/alice/app", idle("alice", "s1").get("dir").asString());

        assertEquals(200, ask("alice", "s2", "plain", null).statusCode());
        assertFalse(idle("alice", "s2").hasNonNull("dir"), "no directory");
        assertFalse(idle("alice", "s2").get("archived").asBoolean());
    }

    @Test
    void renamingIsRecordedAndAnEmptyNameRestoresTheTitle() throws Exception {
        ask("alice", "s1", "review the uncommitted code", null);
        idle("alice", "s1");
        assertEquals(404, send("bob", "PATCH", "/api/sessions/s1", "{\"title\":\"mine\"}").statusCode());
        assertEquals(400, send("alice", "PATCH", "/api/sessions/s1", "{\"title\":\"" + "n".repeat(81) + "\"}").statusCode());

        HttpResponse<String> renamed = send("alice", "PATCH", "/api/sessions/s1", "{\"title\":\"  Quattro review \"}");
        assertEquals(200, renamed.statusCode(), renamed.body());
        assertEquals("Quattro review", waitFor("alice", "s1", item -> item.get("title").asString().equals("Quattro review"))
                .get("title").asString());

        send("alice", "PATCH", "/api/sessions/s1", "{\"title\":\"\"}");
        assertEquals("review the uncommitted code",
                waitFor("alice", "s1", item -> !item.get("title").asString().equals("Quattro review")).get("title").asString());
    }

    @Test
    void aRunningSessionCannotBeArchivedAndAnArchivedOneTakesNoPrompts() throws Exception {
        gate = new CountDownLatch(1);
        ask("alice", "s1", "slow", null);
        waitFor("alice", "s1", item -> !item.get("runStatus").asString().equals("idle"));
        assertEquals("session/archive-busy", code(send("alice", "PUT", "/api/sessions/s1/archived", "{\"archived\":true}")));
        gate.countDown();
        idle("alice", "s1");

        assertEquals(404, send("bob", "PUT", "/api/sessions/s1/archived", "{\"archived\":true}").statusCode());
        assertEquals(400, send("alice", "PUT", "/api/sessions/s1/archived", "{\"archived\":\"yes\"}").statusCode());
        assertEquals(200, send("alice", "PUT", "/api/sessions/s1/archived", "{\"archived\":true}").statusCode());
        assertTrue(session("alice", "s1").get("archived").asBoolean());
        assertEquals("session/archived", code(ask("alice", "s1", "again", null)));

        assertEquals(200, send("alice", "PUT", "/api/sessions/s1/archived", "{\"archived\":false}").statusCode());
        assertEquals(200, ask("alice", "s1", "again", null).statusCode());
    }

    /** session-find-pin R1：置顶进列表；已归档的不能置顶；归档取消置顶，取消归档不恢复。 */
    @Test
    void pinningIsPerPersonAndArchivingUnpins() throws Exception {
        ask("alice", "s1", "hi", null);
        assertFalse(idle("alice", "s1").get("pinned").asBoolean());
        assertEquals(404, send("bob", "PUT", "/api/sessions/s1/pinned", "{\"pinned\":true}").statusCode());
        assertEquals(400, send("alice", "PUT", "/api/sessions/s1/pinned", "{\"pinned\":1}").statusCode());
        assertEquals(200, send("alice", "PUT", "/api/sessions/s1/pinned", "{\"pinned\":true}").statusCode());
        assertTrue(session("alice", "s1").get("pinned").asBoolean());

        assertEquals(200, send("alice", "PUT", "/api/sessions/s1/archived", "{\"archived\":true}").statusCode());
        assertFalse(session("alice", "s1").get("pinned").asBoolean());
        assertEquals("session/archived", code(send("alice", "PUT", "/api/sessions/s1/pinned", "{\"pinned\":true}")));
        assertEquals(200, send("alice", "PUT", "/api/sessions/s1/archived", "{\"archived\":false}").statusCode());
        assertFalse(session("alice", "s1").get("pinned").asBoolean());
        assertEquals(200, send("alice", "PUT", "/api/sessions/s1/pinned", "{\"pinned\":false}").statusCode());
    }

    /** session-find-pin R3：按内容搜只看自己的会话，带片段和角色；空的、太长的搜索词 400。 */
    @Test
    void searchingByContentFindsOnlyYourOwnSessions() throws Exception {
        ask("alice", "s1", "where is the invoice total", null);
        ask("bob", "b1", "invoice for bob", null);
        idle("alice", "s1");
        idle("bob", "b1");
        JsonNode found = JSON.readTree(server.get("alice", "/api/sessions/search?q=INVOICE").body());
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (found.get("hits").isEmpty() && System.nanoTime() < until) {
            Thread.sleep(20);
            found = JSON.readTree(server.get("alice", "/api/sessions/search?q=INVOICE").body());
        }
        assertEquals(1, found.get("hits").size(), found.toString());
        assertEquals("s1", found.get("hits").get(0).get("session").asString());
        assertEquals("user", found.get("hits").get(0).get("role").asString());
        assertEquals("where is the invoice total", found.get("hits").get(0).get("snippet").asString());
        assertFalse(found.get("more").asBoolean());
        assertEquals(400, server.get("alice", "/api/sessions/search?q=%20").statusCode());
        assertEquals(400, server.get("alice", "/api/sessions/search?q=" + "x".repeat(201)).statusCode());
    }
}
