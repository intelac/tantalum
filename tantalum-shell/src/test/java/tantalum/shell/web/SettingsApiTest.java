package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.model.WindowSource;
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

    /** 模型报告的窗口盖过配置；关掉 window-from-model 时只用配置（测试要小窗口）。 */
    @Test
    void aReportedWindowWinsUnlessReadingIsTurnedOff() throws Exception {
        ModelClient reporting = new ModelClient() {
            @Override
            public ModelOutcome call(tantalum.value.model.Prompt prompt, tantalum.shell.model.ChunkListener chunks) {
                return new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);
            }

            @Override
            public WindowSource window() {
                return () -> OptionalLong.of(1_000_000);
            }
        };
        try (TestServer reported = new TestServer(temp.resolve("data-reported"), temp.resolve("ws-reported"), reporting)) {
            JsonNode config = JSON.readTree(reported.get("alice", "/api/config").body());
            assertEquals(1_000_000, config.get("contextWindow").asLong());
            assertEquals("model", config.get("windowSource").asString());
            assertEquals(936_000, JSON.readTree(reported.get("alice", "/api/settings").body()).get("compaction").get("points").get(3)
                    .get("tokens").asLong());
        }
        try (TestServer configured = new TestServer(temp.resolve("data-configured"), temp.resolve("ws-configured"), reporting,
                "--tantalum.compaction.window-from-model=false")) {
            JsonNode config = JSON.readTree(configured.get("alice", "/api/config").body());
            assertEquals(200_000, config.get("contextWindow").asLong());
            assertEquals("config", config.get("windowSource").asString());
        }
    }

    @Test
    void configSaysWhereTheWindowCameFrom() throws Exception {
        JsonNode config = JSON.readTree(server.get("alice", "/api/config").body());
        assertEquals(200_000, config.get("contextWindow").asLong());
        assertEquals("config", config.get("windowSource").asString());
    }
}
