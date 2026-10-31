package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Files;
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

class SpaControllerTest {

    @TempDir
    Path temp;

    private TestServer server;

    private final ModelClient model = (prompt, chunks) ->
            new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("workspace")), model);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void frontendRoutesFallBackToIndexWithoutLogin() throws Exception {
        for (String path : List.of("/", "/login", "/s/abc", "/s/abc/trace/run-0")) {
            HttpResponse<String> response = server.get(null, path);
            assertEquals(200, response.statusCode(), path);
            assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("text/html"), path);
            assertTrue(response.body().contains("id=\"root\""), path);
        }
    }

    @Test
    void apiPathsAreNotSwallowed() throws Exception {
        assertEquals(401, server.get(null, "/api/sessions").statusCode());
        HttpResponse<String> missing = server.get("alice", "/api/nothing-here");
        assertEquals(404, missing.statusCode());
        assertTrue(!missing.body().contains("<html"), missing.body());
    }
}
