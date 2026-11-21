package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

/** memory-final-plan 第 11 节：“不进记忆”的会话。第一次提问带 offRecord 才算数；这种会话的轮次不交给个人插件（记忆不记笔记）。 */
class OffRecordApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private TestServer server;
    private final List<String> noted = new CopyOnWriteArrayList<>();

    private final ModelClient model = (prompt, chunks) -> {
        if (prompt.system().startsWith("You keep notes for a user's long-term memory")) {
            noted.add(prompt.messages().getLast().toString());
            return new ModelOutcome.Replied(List.of(new TextBlock("- 记下了")), StopReason.END_TURN, "m", null);
        }
        return new ModelOutcome.Replied(List.of(new TextBlock("好的")), StopReason.END_TURN, "m", null);
    };

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("workspace")), model,
                "--tantalum.memory-capture.enabled=true");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private void ask(String session, String text, boolean offRecord) throws Exception {
        String body = "{\"requestId\":\"" + text.hashCode() + session + "\",\"delivery\":\"queue\",\"text\":\"" + text + "\""
                + (offRecord ? ",\"offRecord\":true" : "") + "}";
        HttpResponse<String> sent = server.http.send(server.request("alice", "/api/sessions/" + session + "/submissions")
                .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, sent.statusCode(), sent.body());
        for (int i = 0; i < 1000; i++) {
            JsonNode runs = JSON.readTree(server.get("alice", "/api/sessions/" + session + "/runs").body()).get("runs");
            if (runs != null && runs.size() > 0 && runs.get(runs.size() - 1).has("endReason")) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the run did not end");
    }

    private boolean notedSoon(String text) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            if (noted.stream().anyMatch(prompt -> prompt.contains(text))) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    @Test
    void anOffRecordSessionIsNeverNotedAndOnlyTheFirstPromptDecides() throws Exception {
        ask("secret", "我在找新工作", true);
        ask("secret", "别告诉别人", false);
        ask("normal", "今天开会了", false);
        ask("normal", "后来又改主意", true);
        assertTrue(notedSoon("今天开会了"), "a normal session is noted");
        assertTrue(notedSoon("后来又改主意"), "offRecord on a later prompt does not change the session");
        assertTrue(noted.stream().noneMatch(prompt -> prompt.contains("新工作") || prompt.contains("别告诉别人")),
                "the off-record session was sent to the note taker: " + noted);
    }
}
