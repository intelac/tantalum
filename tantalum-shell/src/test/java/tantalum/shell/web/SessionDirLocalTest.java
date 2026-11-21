package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * workspaces 任务 15（W5、W6、需求 1.1–1.3、2.3–2.6）：会话在工作区里时，电脑按会话目录读相对路径、读 {@code AGENTS.md}，
 * 给模型的那一段写工作目录；协议 2 的电脑照旧用主目录；添加框经电脑列目录，不存在的目录登记不上。
 */
class SessionDirLocalTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private Path main;
    private Path app;
    private TestServer server;
    private final List<String> systems = new CopyOnWriteArrayList<>();

    /** 先读 a.txt，拿到结果后把结果原样说出来。 */
    private final ModelClient model = (prompt, chunks) -> {
        systems.add(prompt.system());
        ContentBlock last = prompt.messages().getLast().content().getFirst();
        if (last instanceof ToolResultBlock result) {
            return new ModelOutcome.Replied(List.of(new TextBlock("It says: " + result.text())), StopReason.END_TURN, "m", null);
        }
        return new ModelOutcome.Replied(List.of(new ToolCallBlock("c1", "read_file", "{\"path\":\"a.txt\"}",
                new Json.Obj(Map.of("path", new Json.Str("a.txt"))))), StopReason.TOOL_USE, "m", null);
    };

    @BeforeEach
    void start() throws Exception {
        main = Files.createDirectory(temp.resolve("computer")).toRealPath();
        app = Files.createDirectory(main.resolve("app"));
        Files.createDirectory(main.resolve(".hidden"));
        Files.writeString(main.resolve("a.txt"), "from the main directory");
        Files.writeString(app.resolve("a.txt"), "from the app");
        Files.writeString(main.resolve("AGENTS.md"), "main rules");
        Files.writeString(app.resolve("AGENTS.md"), "app rules");
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    /** 路径放进查询串要编码：Windows 路径里的 {@code \\} 不能直接出现在 URL 里。 */
    private static String query(Path path) {
        return URLEncoder.encode(path.toString(), StandardCharsets.UTF_8);
    }

    private String project(Path path) throws Exception {
        HttpResponse<String> added = server.post("alice", "/api/projects", "{\"path\":" + JSON.writeValueAsString(path.toString()) + "}");
        assertEquals(201, added.statusCode(), added.body());
        return JSON.readTree(added.body()).get("id").asString();
    }

    private String ask(String session, String project) throws Exception {
        server.post("alice", "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"" + session + "\",\"delivery\":\"queue\",\"text\":\"read a\",\"project\":\"" + project + "\"}");
        String body = "";
        for (int i = 0; i < 500 && !body.contains("\"endReason\":\"COMPLETED\""); i++) {
            Thread.sleep(10);
            body = server.get("alice", "/api/sessions/" + session + "/messages").body();
        }
        assertTrue(body.contains("\"endReason\":\"COMPLETED\""), body);
        return body;
    }

    @Test
    void aSessionInAWorkspaceWorksFromItsDirectory() throws Exception {
        server.connectComputer("alice", main, false);
        String body = ask("s1", project(app));
        assertTrue(body.contains("from the app"), body);
        assertFalse(body.contains("from the main directory"), body);
        String system = systems.getFirst();
        assertTrue(system.contains("Working directory: " + app + "."), system);
        assertTrue(system.contains("app rules"), system);
        assertFalse(system.contains("main rules"), system);
    }

    @Test
    void anOldConnectorKeepsUsingTheMainDirectoryAndSaysSo() throws Exception {
        server.connectComputer("alice", main, false, 2);
        String body = ask("s1", project(app));
        assertTrue(body.contains("from the main directory"), body);
        String system = systems.getFirst();
        assertTrue(system.contains("Main directory: " + main + "."), system);
        assertFalse(system.contains("Working directory"), system);
        JsonNode status = JSON.readTree(server.get("alice", "/api/local").body());
        assertEquals(2, status.path("link").path("protocol").asInt(), status.toString());
        assertFalse(status.path("link").path("current").asBoolean(), status.toString());
        assertEquals("local/protocol-old", JSON.readTree(server.get("alice", "/api/projects/browse").body()).path("code").asString());
    }

    @Test
    void theAddDialogBrowsesTheComputerAndRefusesFoldersItDoesNotHave() throws Exception {
        assertEquals("local/not-connected", JSON.readTree(server.get("alice", "/api/projects/browse").body()).path("code").asString());
        assertEquals(201, server.post("alice", "/api/projects", "{\"path\":\"/not/checked/while/offline\"}").statusCode(),
                "without a computer the path is stored unchecked");

        server.connectComputer("alice", main, false);
        JsonNode roots = JSON.readTree(server.get("alice", "/api/projects/browse").body());
        assertEquals(List.of(main.toString()), roots.get("dirs").valueStream().map(JsonNode::asString).toList());
        JsonNode listed = JSON.readTree(server.get("alice", "/api/projects/browse?path=" + query(main)).body());
        assertEquals(List.of(app.toString()), listed.get("dirs").valueStream().map(JsonNode::asString).toList(), "hidden folders are left out");
        assertEquals("project/path-not-allowed",
                JSON.readTree(server.get("alice", "/api/projects/browse?path=" + query(temp.toRealPath())).body()).path("code").asString());

        HttpResponse<String> missing = server.post("alice", "/api/projects", "{\"path\":" + JSON.writeValueAsString(main.resolve("nope").toString()) + "}");
        assertEquals("project/path-not-allowed", JSON.readTree(missing.body()).path("code").asString());
    }
}
