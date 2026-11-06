package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.boot.Engine;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.plugin.Question;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.Workspace;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A4：对话页上取问题、交回答的接口。只看得到、只答得了自己的；别人的和已经不在的一样 404。 */
class QuestionApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Question APPROVE = new Question("approval-c1", "允许调用 bash 吗？", "{}",
            List.of(new Question.Choice("once", "允许这一次"), new Question.Choice("deny", "拒绝")));

    @TempDir
    Path temp;

    private TestServer server;

    @BeforeEach
    void start() {
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"),
                (prompt, chunks) -> new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null));
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

    @Test
    void onlyTheAskedUserSeesAndAnswersTheQuestion() throws Exception {
        server.get("bob", "/api/sessions");
        Engine engine = server.context.getBean(Engine.class);
        ToolContext alice = new ToolContext(new Principal("alice"), new SessionId("s1"),
                new Workspace(Files.createDirectories(temp.resolve("alice"))), engine.questions());
        CompletableFuture<Optional<String>> asked = CompletableFuture.supplyAsync(() -> {
            try {
                return engine.questions().ask(alice, APPROVE, Duration.ofSeconds(20));
            } catch (InterruptedException stopped) {
                throw new IllegalStateException(stopped);
            }
        });
        JsonNode questions = open("alice");
        for (int i = 0; i < 200 && questions.isEmpty(); i++) {
            Thread.sleep(10);
            questions = open("alice");
        }
        assertEquals("approval-c1", questions.get(0).get("id").asString());
        assertEquals("允许这一次", questions.get(0).get("choices").get(0).get("label").asString());
        assertTrue(open("bob").isEmpty(), "bob does not see alice's question");

        assertEquals(404, server.post("bob", "/api/sessions/s1/questions/approval-c1", "{\"choice\":\"once\"}").statusCode());
        assertEquals(400, server.post("alice", "/api/sessions/s1/questions/approval-c1", "{\"choice\":\"always\"}").statusCode());
        assertEquals(400, server.post("alice", "/api/sessions/s1/questions/approval-c1", "{}").statusCode());
        assertEquals(200, server.post("alice", "/api/sessions/s1/questions/approval-c1", "{\"choice\":\"deny\"}").statusCode());
        assertEquals(Optional.of("deny"), asked.get(5, TimeUnit.SECONDS));
        assertEquals(404, server.post("alice", "/api/sessions/s1/questions/approval-c1", "{\"choice\":\"once\"}").statusCode());
        assertTrue(open("alice").isEmpty());
    }
}
