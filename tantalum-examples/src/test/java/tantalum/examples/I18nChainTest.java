package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.web.TestServer;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/**
 * console-i18n 需求 4.8：给模型的文字不随控制台的语言变。三个用户分别把语言设成中文、英文、日文，同样提问，
 * 模型收到的请求（system、工具说明、消息、输出约束、模型选择）逐字相同。
 */
class I18nChainTest {

    @TempDir
    Path temp;

    private final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());
    private TestServer server;

    @BeforeEach
    void start() {
        ModelClient model = (prompt, chunks) -> {
            prompts.add(prompt);
            return new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);
        };
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void theModelSeesTheSameRequestWhateverTheConsoleLanguage() throws Exception {
        Map<String, String> users = new LinkedHashMap<>();
        users.put("zhuser", "zh");
        users.put("enuser", "en");
        users.put("jauser", "ja");
        List<Prompt> seen = new ArrayList<>();
        for (Map.Entry<String, String> user : users.entrySet()) {
            HttpResponse<String> saved = server.http.send(server.request(user.getKey(), "/api/settings/language")
                    .header("content-type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"language\":\"" + user.getValue() + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, saved.statusCode(), saved.body());
            int before = prompts.size();
            // 会话 ID 属于第一个认领的用户，所以各用各的；它不进发给模型的请求
            ask(user.getKey(), "s-" + user.getValue(), "Summarize the release notes in three bullet points.");
            assertEquals(before + 1, prompts.size(), "one model call per question");
            seen.add(prompts.get(before));
        }
        assertEquals(seen.get(0), seen.get(1), "Chinese and English consoles send the same request");
        assertEquals(seen.get(0), seen.get(2), "Chinese and Japanese consoles send the same request");
    }

    private void ask(String user, String session, String text) throws Exception {
        String body = "{\"requestId\":\"r-" + user + "\",\"mode\":\"prompt\",\"text\":\"" + text + "\"}";
        assertEquals(200, server.post(user, "/api/sessions/" + session + "/submissions", body).statusCode());
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            if (server.get(user, "/api/sessions/" + session + "/runs").body().contains("COMPLETED")) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("run of " + user + " did not finish");
    }
}
