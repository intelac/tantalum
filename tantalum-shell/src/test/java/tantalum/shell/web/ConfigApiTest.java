package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ConfigApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private final ModelClient model = (prompt, chunks) ->
            new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);

    @Test
    void claudeConfigNamesProviderAndModelWithoutAnyKey() throws Exception {
        try (TestServer server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("w")), model)) {
            HttpResponse<String> response = server.get("alice", "/api/config");
            assertEquals(200, response.statusCode());
            JsonNode body = JSON.readTree(response.body());
            assertEquals("claude", body.get("provider").asString());
            assertEquals("claude-sonnet-5-5", body.get("model").asString());
            assertEquals(true, body.get("cloud").asBoolean());
            assertEquals(200000, body.get("contextWindow").asLong());
            assertEquals(4, body.size());
            assertFalse(response.body().toLowerCase(Locale.ROOT).contains("key"), response.body());
            assertFalse(response.body().contains("sk-"), response.body());
            assertEquals(401, server.get(null, "/api/config").statusCode());
        }
    }

    @Test
    void openAiProviderIsLocal() throws Exception {
        try (TestServer server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("w")), model,
                "--tantalum.provider=openai", "--tantalum.openai.model=qwen-test", "--tantalum.openai.context-window=0")) {
            JsonNode body = JSON.readTree(server.get("alice", "/api/config").body());
            assertEquals("openai", body.get("provider").asString());
            assertEquals("qwen-test", body.get("model").asString());
            assertEquals(false, body.get("cloud").asBoolean());
            assertFalse(body.has("contextWindow"));
        }
    }
}
