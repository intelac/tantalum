package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;

/** 需求 11 第 2、6 条：一个用户的运行读不到另一个用户的文件。 */
class WorkspaceIsolationTest {

    private static final String SECRET = "alice-file-content";

    @TempDir
    Path temp;

    private Path workspace;
    private TestServer server;

    /** 第一轮按提问读取文件，第二轮把工具结果原样作为回答。 */
    private final ModelClient model = (prompt, chunks) -> {
        Message last = prompt.messages().getLast();
        ContentBlock block = last.content().getFirst();
        if (block instanceof ToolResultBlock result) {
            return new ModelOutcome.Replied(List.of(new TextBlock((result.error() ? "ERROR: " : "OK: ") + result.text())),
                    StopReason.END_TURN, "m", null);
        }
        String path = ((TextBlock) block).text();
        return new ModelOutcome.Replied(List.of(new ToolCallBlock("c", "read_file", "{}",
                new Json.Obj(Map.of("path", new Json.Str(path))))), StopReason.TOOL_USE, "m", null);
    };

    @BeforeEach
    void start() throws Exception {
        workspace = Files.createDirectory(temp.resolve("workspace"));
        Files.createDirectory(workspace.resolve("alice"));
        Files.writeString(workspace.resolve("alice/secret.txt"), SECRET);
        server = new TestServer(temp.resolve("data"), workspace, model);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private String read(String user, String session, String path) throws Exception {
        assertEquals(200, server.post(user, "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"r\",\"mode\":\"prompt\",\"text\":\"" + path + "\"}").statusCode());
        String runs = "";
        for (int i = 0; i < 500 && !runs.contains("COMPLETED"); i++) {
            Thread.sleep(10);
            runs = server.get(user, "/api/sessions/" + session + "/runs").body();
        }
        assertTrue(runs.contains("COMPLETED"), runs);
        int start = runs.indexOf(session + "_");
        String run = runs.substring(start, runs.indexOf('"', start));
        return server.get(user, "/api/runs/" + run + "/turns/2").body();
    }

    @Test
    void ownerCanReadTheirOwnFile() throws Exception {
        String answer = read("alice", "a1", "secret.txt");
        assertTrue(answer.contains("OK: " + SECRET), answer);
    }

    @Test
    void relativePathCannotReachAnotherUser() throws Exception {
        String answer = read("bob", "b1", "../alice/secret.txt");
        assertTrue(answer.contains("ERROR: "), answer);
        assertFalse(answer.contains(SECRET), answer);
    }

    @Test
    void absolutePathCannotReachAnotherUser() throws Exception {
        String answer = read("bob", "b2", workspace.toRealPath().resolve("alice/secret.txt").toString());
        assertTrue(answer.contains("ERROR: "), answer);
        assertFalse(answer.contains(SECRET), answer);
    }

    @Test
    void symlinkCannotReachAnotherUser() throws Exception {
        Files.createDirectories(workspace.resolve("bob"));
        Files.createSymbolicLink(workspace.resolve("bob/link.txt"), workspace.resolve("alice/secret.txt"));
        String answer = read("bob", "b3", "link.txt");
        assertTrue(answer.contains("ERROR: "), answer);
        assertFalse(answer.contains(SECRET), answer);
    }

    @Test
    void agentsFileIsPerUser() throws Exception {
        Files.writeString(workspace.resolve("alice/AGENTS.md"), "alice-only-rules");
        assertTrue(read("alice", "a2", "AGENTS.md").contains("alice-only-rules"));
        String runs = "";
        read("bob", "b4", "missing.txt");
        runs = server.get("bob", "/api/sessions/b4/runs").body();
        int start = runs.indexOf("b4_");
        String turn = server.get("bob", "/api/runs/" + runs.substring(start, runs.indexOf('"', start)) + "/turns/1").body();
        assertFalse(turn.contains("alice-only-rules"), turn);
    }
}
