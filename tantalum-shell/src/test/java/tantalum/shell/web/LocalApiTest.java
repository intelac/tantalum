package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.boot.Engine;
import tantalum.shell.local.LocalTokens;
import tantalum.shell.local.TestComputer;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 插件页的本机工作区接口（local-workspace 任务 16，L2、L17，需求 1.1、6.4）。 */
class LocalApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    private HttpResponse<String> send(String user, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = server.request(user, path).header("content-type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return server.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode status(String user) throws Exception {
        HttpResponse<String> response = server.get(user, "/api/local");
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    @Test
    void anonymousCallersGetNothing() throws Exception {
        assertEquals(401, server.get(null, "/api/local").statusCode());
        assertEquals(401, send(null, "POST", "/api/local/tokens", "{\"validity\":\"1h\"}").statusCode());
        assertEquals(401, send(null, "DELETE", "/api/local/tokens", null).statusCode());
    }

    /** 需求 1.1：令牌和命令只在这次响应里；命令里有服务器地址，令牌放在环境变量里，响应不缓存。 */
    @Test
    void aTokenComesWithTheCommandOnce() throws Exception {
        assertEquals(400, send("alice", "POST", "/api/local/tokens", "{\"validity\":\"2h\"}").statusCode());
        assertEquals(400, send("alice", "POST", "/api/local/tokens", "not json").statusCode());

        Instant before = Instant.now();
        HttpResponse<String> response = send("alice", "POST", "/api/local/tokens", "{\"validity\":\"1h\"}");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("cache-control").orElse(""));
        JsonNode body = JSON.readTree(response.body());
        String token = body.get("token").asString();
        assertTrue(token.startsWith(LocalTokens.PREFIX), token);
        assertEquals("TANTALUM_TOKEN=" + token + " tantalum-connect --server http://127.0.0.1:" + server.port() + " --allow .",
                body.get("command").asString());
        Instant expiresAt = Instant.parse(body.get("expiresAt").asString());
        assertTrue(!expiresAt.isBefore(before.plus(Duration.ofHours(1))) && expiresAt.isBefore(Instant.now().plus(Duration.ofHours(1)).plusSeconds(1)));

        Engine engine = server.context.getBean(Engine.class);
        assertEquals(LocalTokens.Status.VALID, engine.localLinks().tokens().verify(token).status());
        assertFalse(server.get("alice", "/api/local").body().contains(token), "the status never shows the token");
    }

    /** 另一个站点的页面不能替用户生成令牌（Origin 和主机不一致）。 */
    @Test
    void anotherSiteCannotIssueATokenForTheUser() throws Exception {
        HttpRequest request = server.request("alice", "/api/local/tokens").header("content-type", "application/json")
                .header("Origin", "https://evil.example").POST(HttpRequest.BodyPublishers.ofString("{\"validity\":\"1d\"}")).build();
        assertEquals(403, server.http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    /** 需求 1.2、6.4：看得到自己的电脑，看不到别人的；撤销后连接断开、令牌作废。 */
    @Test
    void statusShowsOnlyYourOwnComputerAndRevokingEndsIt() throws Exception {
        JsonNode empty = status("alice");
        assertTrue(empty.get("link").isNull(), empty.toString());
        assertEquals("{\"local-workspace\":true,\"fs-read\":true,\"git\":true,\"file-edit\":true,\"shell\":true}",
                empty.get("switches").toString());

        String token = JSON.readTree(send("alice", "POST", "/api/local/tokens", "{\"validity\":\"30m\"}").body()).get("token").asString();
        Path mac = Files.createDirectories(temp.resolve("mac"));
        TestComputer computer = server.connectComputer("alice", mac, true);
        JsonNode link = status("alice").get("link");
        assertEquals("Test Mac", link.get("device").get("name").asString());
        assertEquals("macos", link.get("device").get("os").asString());
        assertEquals(mac.toRealPath().toString(), link.get("roots").get(0).get("path").asString());
        assertTrue(link.get("write").asBoolean());
        assertFalse(link.get("shell").get("available").asBoolean());
        assertEquals("--allow-shell was not given", link.get("shell").get("reason").asString());
        assertFalse(link.get("shell").has("code"), "an old connector sends only the reason; the console shows it as is");
        assertTrue(link.has("connectedAt") && link.has("expiresAt"), link.toString());
        assertTrue(status("bob").get("link").isNull(), "bob does not see alice's computer");

        assertEquals(200, send("bob", "DELETE", "/api/local/tokens", null).statusCode());
        assertFalse(computer.closed(), "bob's revoke does not touch alice");

        assertEquals(200, send("alice", "DELETE", "/api/local/tokens", null).statusCode());
        assertTrue(computer.closed());
        assertTrue(status("alice").get("link").isNull());
        assertEquals(LocalTokens.Status.REVOKED, server.context.getBean(Engine.class).localLinks().tokens().verify(token).status());
    }

    /** 开关照用插件页的接口，状态在这里读。 */
    @Test
    void switchesAreSetThroughThePluginsApi() throws Exception {
        HttpRequest put = server.request("alice", "/api/plugins/local/shell").header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"enabled\":false}")).build();
        assertEquals(200, server.http.send(put, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertFalse(status("alice").get("switches").get("shell").asBoolean());
        assertTrue(status("bob").get("switches").get("shell").asBoolean());
    }
}
