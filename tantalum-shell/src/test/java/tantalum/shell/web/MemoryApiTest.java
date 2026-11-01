package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class MemoryApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ModelClient IDLE = (prompt, chunks) ->
            new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);

    @TempDir
    Path temp;

    private TestServer server;

    @BeforeEach
    void start() {
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), IDLE);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static String entry(String name, String type, String description, String content) {
        return "{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"description\":\"" + description + "\",\"content\":\"" + content + "\"}";
    }

    private HttpResponse<String> delete(String user, String name) throws Exception {
        return server.http.send(server.request(user, "/api/memory/" + name).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void createListReadDelete() throws Exception {
        HttpResponse<String> created = server.post("alice", "/api/memory", entry("java", "user", "Java 25 only", "Always Java 25."));
        assertEquals(201, created.statusCode(), created.body());
        JsonNode body = JSON.readTree(created.body());
        assertEquals("java", body.get("name").asString());
        assertEquals("user", body.get("type").asString());
        assertTrue(body.has("updatedAt"));

        server.post("alice", "/api/memory", entry("cobol", "project", "COBOL rules", "Copybooks live in cpy/."));
        JsonNode list = JSON.readTree(server.get("alice", "/api/memory").body());
        assertEquals(2, list.get("entries").size());
        assertEquals(2, list.get("used").asInt());
        assertEquals(200, list.get("max").asInt());
        assertEquals("cobol", list.get("entries").get(0).get("name").asString(), "newest first");
        assertFalse(list.get("entries").get(0).has("content"), "list carries summaries only");

        JsonNode read = JSON.readTree(server.get("alice", "/api/memory/java").body());
        assertEquals("Always Java 25.", read.get("content").asString());

        HttpResponse<String> overwritten = server.post("alice", "/api/memory", entry("java", "user", "Java 25 only", "changed"));
        assertEquals(200, overwritten.statusCode());

        assertEquals(204, delete("alice", "java").statusCode());
        assertEquals(404, server.get("alice", "/api/memory/java").statusCode());
        assertEquals(404, delete("alice", "java").statusCode());
    }

    @Test
    void validationAndCredentialFilterGive400WithTheReason() throws Exception {
        assertEquals(400, server.post("alice", "/api/memory", "{oops").statusCode());
        HttpResponse<String> badName = server.post("alice", "/api/memory", entry("Bad Name", "user", "d", "c"));
        assertEquals(400, badName.statusCode());
        assertTrue(badName.body().contains("name"), badName.body());
        assertTrue(server.post("alice", "/api/memory", entry("x", "video", "d", "c")).body().contains("type"));
        assertTrue(server.post("alice", "/api/memory", "{\"name\":\"x\",\"type\":\"user\",\"content\":\"c\"}").body().contains("description"));
        HttpResponse<String> secret = server.post("alice", "/api/memory", entry("x", "user", "d", "password=hunter2secret"));
        assertEquals(400, secret.statusCode());
        assertTrue(secret.body().contains("credentials"), secret.body());
        assertFalse(secret.body().contains("hunter2"));
        assertEquals(0, JSON.readTree(server.get("alice", "/api/memory").body()).get("used").asInt());
    }

    @Test
    void usersOnlySeeTheirOwnAndMissingLooksTheSame() throws Exception {
        server.post("alice", "/api/memory", entry("alice-fact", "user", "d", "c"));
        assertEquals(0, JSON.readTree(server.get("bob", "/api/memory").body()).get("used").asInt());
        HttpResponse<String> others = server.get("bob", "/api/memory/alice-fact");
        HttpResponse<String> missing = server.get("bob", "/api/memory/nope");
        assertEquals(404, others.statusCode());
        assertEquals(others.body(), missing.body());
        assertEquals(404, delete("bob", "alice-fact").statusCode());
        assertEquals(200, server.get("alice", "/api/memory/alice-fact").statusCode());
        // 名字不合法按不存在处理；带编码斜杠的路径由 Spring 的防火墙直接拒绝（400），不会到控制器。
        assertEquals(404, server.get("alice", "/api/memory/Not-Valid").statusCode());
        assertEquals(400, server.get("alice", "/api/memory/..%2Fother").statusCode());
    }

    @Test
    void requiresLoginAndSameOriginForWrites() throws Exception {
        assertEquals(401, server.get(null, "/api/memory").statusCode());
        server.get("alice", "/api/memory");  // 登录
        HttpRequest cross = server.request("alice", "/api/memory").header("Origin", "https://evil.example")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(entry("x", "user", "d", "c"))).build();
        assertEquals(403, server.http.send(cross, HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpRequest crossDelete = server.request("alice", "/api/memory/x").header("Origin", "https://evil.example").DELETE().build();
        assertEquals(403, server.http.send(crossDelete, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0, JSON.readTree(server.get("alice", "/api/memory").body()).get("used").asInt());
    }
}
