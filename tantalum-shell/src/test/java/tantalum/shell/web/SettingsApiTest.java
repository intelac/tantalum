package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
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

/**
 * compaction-threshold 需求 1.8、2：设置接口和 {@code /api/config} 的窗口。替身模型读不到窗口，所以是配置的 200000、
 * 来源 config；Claude 的预留是 max-tokens 64000。
 */
class SettingsApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private TestServer server;

    @BeforeEach
    void start() {
        ModelClient model = (prompt, chunks) -> new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> put(String user, String body) throws Exception {
        return server.http.send(server.request(user, "/api/settings/compaction").header("content-type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode settings(String user) throws Exception {
        HttpResponse<String> response = server.get(user, "/api/settings");
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body()).get("compaction");
    }

    @Test
    void theDefaultIsFullAndEveryPointIsListed() throws Exception {
        JsonNode compaction = settings("alice");
        assertEquals(100, compaction.get("percent").asInt());
        assertEquals("[{\"percent\":50,\"tokens\":100000},{\"percent\":70,\"tokens\":136000},{\"percent\":85,\"tokens\":136000},"
                + "{\"percent\":100,\"tokens\":136000}]", compaction.get("points").toString(), "min(W × percent, W − R)");
    }

    @Test
    void aChoiceIsSavedForThatUserOnly() throws Exception {
        HttpResponse<String> saved = put("alice", "{\"percent\":50}");
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals(50, JSON.readTree(saved.body()).get("compaction").get("percent").asInt());
        assertEquals(50, settings("alice").get("percent").asInt());
        assertEquals(100, settings("bob").get("percent").asInt());
    }

    @Test
    void anythingButTheFourChoicesIsRefused() throws Exception {
        for (String body : new String[] {"{\"percent\":60}", "{\"percent\":\"50\"}", "{}", "not json", "{\"percent\":50.5}"}) {
            assertEquals(400, put("alice", body).statusCode(), body);
        }
        assertEquals(100, settings("alice").get("percent").asInt());
    }

    @Test
    void signedOutGetsNothing() throws Exception {
        assertEquals(401, server.get(null, "/api/settings").statusCode());
        assertEquals(401, put(null, "{\"percent\":50}").statusCode());
    }

    private HttpResponse<String> putLanguage(String user, String body) throws Exception {
        return server.http.send(server.request(user, "/api/settings/language").header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode language(String user) throws Exception {
        return JSON.readTree(server.get(user, "/api/settings").body()).get("language");
    }

    /** console-i18n 需求 1.3–1.5、1.9：默认 null（跟随浏览器），按用户存，null 删掉，三种以外 400。 */
    @Test
    void theLanguageIsStoredPerUser() throws Exception {
        assertTrue(language("alice").isNull());
        HttpResponse<String> saved = putLanguage("alice", "{\"language\":\"ja\"}");
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals("ja", JSON.readTree(saved.body()).get("language").asString());
        assertEquals("ja", language("alice").asString());
        assertTrue(language("bob").isNull());
        assertEquals(200, putLanguage("alice", "{\"language\":null}").statusCode());
        assertTrue(language("alice").isNull());
    }

    @Test
    void onlyTheThreeLanguagesAreAccepted() throws Exception {
        for (String body : new String[] {"{\"language\":\"fr\"}", "{\"language\":\"JA\"}", "{\"language\":1}", "{}", "not json"}) {
            assertEquals(400, putLanguage("alice", body).statusCode(), body);
        }
        assertTrue(language("alice").isNull());
        assertEquals(401, putLanguage(null, "{\"language\":\"ja\"}").statusCode());
    }

    @Test
    void configSaysWhereTheWindowCameFrom() throws Exception {
        JsonNode config = JSON.readTree(server.get("alice", "/api/config").body());
        assertEquals(200_000, config.get("contextWindow").asLong());
        assertEquals("config", config.get("windowSource").asString());
    }
}
