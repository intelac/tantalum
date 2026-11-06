package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.KernelState;
import tantalum.value.text.LocalText;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** core-tools 需求 7：插件页的接口，每个用户只管自己的。 */
class PluginApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 一个必须开启的策略钩子：用户不能关。 */
    static final class Quota implements KernelHook {
        @Override public String name() { return "dailyQuota"; }
        @Override public LocalText description() { return LocalText.of("daily limit"); }
        @Override public boolean mandatory() { return true; }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) { return Allow.PLAIN; }
    }

    static final class Notes implements KernelHook {
        @Override public String name() { return "notes"; }
        @Override public HookResult beforeRunEnd(KernelState state, HookContext context) { return Allow.PLAIN; }
    }

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
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model, context -> {
            context.registerBean("quota", KernelHook.class, Quota::new);
            context.registerBean("notes", KernelHook.class, Notes::new);
        });
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> put(String user, String path, String body) throws Exception {
        return server.http.send(server.request(user, path).header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode plugins(String user) throws Exception {
        HttpResponse<String> response = server.get(user, "/api/plugins");
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static JsonNode named(JsonNode list, String name) {
        for (JsonNode item : list) {
            if (item.get("name").asString().equals(name)) {
                return item;
            }
        }
        throw new AssertionError(name + " not in " + list);
    }

    /** 内置工具只剩服务器自己的（读附件、记忆）；文件和 Git 工具在本机工作区的卡片上，不在这里（local-workspace L17）。 */
    @Test
    void listsBuiltInToolsAndHookPlugins() throws Exception {
        JsonNode body = plugins("alice");
        for (String tool : List.of("read_attachment", "memory_read", "memory_write", "memory_delete")) {
            assertTrue(named(body.get("tools"), tool).get("enabled").asBoolean(), tool);
        }
        for (JsonNode tool : body.get("tools")) {
            assertFalse(List.of("read_file", "grep", "write_file", "edit_file", "git_log").contains(tool.get("name").asString()), tool.toString());
            assertFalse(tool.get("name").asString().equals("web_fetch") || tool.get("name").asString().startsWith("browser_"),
                    "web tools are on their own card: " + tool);
        }
        assertEquals(0, body.get("mcp").size());
        assertTrue(body.get("midSessionChanges").isBoolean());
        JsonNode quota = named(body.get("plugins"), "dailyQuota");
        assertTrue(quota.get("mandatory").asBoolean());
        assertEquals("daily limit", quota.get("description").asString());
        assertEquals("[\"beforeModelCall\"]", quota.get("points").toString());
        assertFalse(named(body.get("plugins"), "notes").get("mandatory").asBoolean());
    }

    /**
     * 插件页的真实返回存成 {@code src/test/resources/wire/plugins.json}，控制台的 {@code plugins.test.tsx} 拿它渲染，
     * 查每个内置工具都有字典里的名字和说明（console-i18n 任务 4）。改了返回格式就用 {@code -Dwire.record=true} 重写这份文件。
     */
    @Test
    void pluginPageAsTheConsoleReadsIt() throws Exception {
        check("plugins.json", JSON.writerWithDefaultPrettyPrinter().writeValueAsString(plugins("alice")) + "\n");
    }

    private static void check(String name, String actual) throws IOException {
        Path file = Path.of("src/test/resources/wire").resolve(name);
        if (Boolean.getBoolean("wire.record")) {
            Files.writeString(file, actual, StandardCharsets.UTF_8);
        }
        assertEquals(Files.readString(file, StandardCharsets.UTF_8), actual, name + " changed; record it again with -Dwire.record=true");
    }

    /**
     * 7.6：开关只影响自己；关掉的工具从下一次组装开始不给这个用户。本机工作区的工具用 {@code local} 开关（local-workspace），
     * 两人都连着允许改文件的电脑，alice 关了“改文件”，只有她的 write_file 不见了。
     */
    @Test
    void switchesBelongToTheUserWhoSetsThem() throws Exception {
        for (String user : List.of("alice", "bob")) {
            server.connectComputer(user, java.nio.file.Files.createDirectories(temp.resolve("mac-" + user)), true);
        }
        HttpResponse<String> off = put("alice", "/api/plugins/tool/memory_write", "{\"enabled\":false}");
        assertEquals(200, off.statusCode(), off.body());
        assertFalse(named(plugins("alice").get("tools"), "memory_write").get("enabled").asBoolean());
        assertTrue(named(plugins("bob").get("tools"), "memory_write").get("enabled").asBoolean());
        HttpResponse<String> local = put("alice", "/api/plugins/local/file-edit", "{\"enabled\":false}");
        assertEquals(200, local.statusCode(), local.body());
        assertEquals(200, put("alice", "/api/plugins/plugin/notes", "{\"enabled\":false}").statusCode());
        assertTrue(named(plugins("bob").get("plugins"), "notes").get("enabled").asBoolean());

        ask("alice", "a1");
        ask("bob", "b1");
        // 工具列表是全集（会话中途换工具时它不能变，core-tools C14）；alice 关掉的工具标 deferred，模型看不见
        assertTrue(spec("alice", "memory_write").deferred());
        assertTrue(spec("alice", "write_file").deferred());
        assertFalse(spec("alice", "read_file").deferred());
        assertFalse(spec("bob", "write_file").deferred());
        assertFalse(spec("bob", "memory_write").deferred());
    }

    /** web-tools 需求 5.1：联网三个开关默认都开；alice 关掉“抓网页”后她的是关的，bob 的还是开的。 */
    @Test
    void webSwitchesDefaultOnAndBelongToTheUser() throws Exception {
        JsonNode web = plugins("alice").get("web");
        assertEquals("{\"web\":true,\"web-fetch\":true,\"browser\":true}", web.get("switches").toString());
        assertEquals("{\"web-fetch\":true,\"browser\":true}", web.get("admin").toString());
        assertFalse(web.get("blockPrivateNetwork").asBoolean());
        assertEquals(3, web.size(), "no proxy or other server details: " + web);

        HttpResponse<String> off = put("alice", "/api/plugins/web/web-fetch", "{\"enabled\":false}");
        assertEquals(200, off.statusCode(), off.body());
        assertFalse(plugins("alice").get("web").get("switches").get("web-fetch").asBoolean());
        assertTrue(plugins("bob").get("web").get("switches").get("web-fetch").asBoolean());
        assertEquals(404, put("alice", "/api/plugins/web/web_fetch", "{\"enabled\":false}").statusCode());
        assertEquals(404, put("alice", "/api/plugins/tool/web_fetch", "{\"enabled\":false}").statusCode(), "no per-tool switch");
        assertEquals(401, put(null, "/api/plugins/web/web", "{\"enabled\":false}").statusCode());
    }

    /** web-tools 需求 5.3：管理员关掉的项 admin 为 false；两项都关时这一块照样返回，开关照样能存。 */
    @Test
    void whatTheAdminTurnedOffIsMarked() throws Exception {
        try (TestServer restricted = new TestServer(temp.resolve("data2"), temp.resolve("ws2"), (prompt, chunks) -> null,
                "--tantalum.web.browser.enabled=false", "--tantalum.web.fetch.enabled=false", "--tantalum.web.block-private-network=true")) {
            HttpResponse<String> response = restricted.get("alice", "/api/plugins");
            assertEquals(200, response.statusCode(), response.body());
            JsonNode web = JSON.readTree(response.body()).get("web");
            assertEquals("{\"web-fetch\":false,\"browser\":false}", web.get("admin").toString());
            assertTrue(web.get("blockPrivateNetwork").asBoolean());
            HttpResponse<String> saved = restricted.http.send(restricted.request("alice", "/api/plugins/web/browser")
                    .header("content-type", "application/json").PUT(HttpRequest.BodyPublishers.ofString("{\"enabled\":false}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, saved.statusCode(), saved.body());
        }
    }

    @Test
    void mandatoryPluginsAndUnknownNamesAreRefused() throws Exception {
        HttpResponse<String> required = put("alice", "/api/plugins/plugin/dailyQuota", "{\"enabled\":false}");
        assertEquals(400, required.statusCode());
        assertTrue(required.body().contains("\"code\":\"plugin/required\",\"params\":{\"name\":\"dailyQuota\"}"), required.body());
        assertTrue(named(plugins("alice").get("plugins"), "dailyQuota").get("enabled").asBoolean());
        HttpResponse<String> unknown = put("alice", "/api/plugins/tool/nope", "{\"enabled\":false}");
        assertEquals(404, unknown.statusCode());
        assertTrue(unknown.body().contains("\"code\":\"plugin/not-found\",\"params\":{\"name\":\"nope\"}"), unknown.body());
        assertEquals(404, put("alice", "/api/plugins/mcp/nope", "{\"enabled\":false}").statusCode());
        assertEquals(404, put("alice", "/api/plugins/hook/notes", "{\"enabled\":false}").statusCode());
        // 本机工作区的工具没有单独的开关，只有 local 的五个
        assertEquals(404, put("alice", "/api/plugins/tool/read_file", "{\"enabled\":false}").statusCode());
        assertEquals(404, put("alice", "/api/plugins/local/nope", "{\"enabled\":false}").statusCode());
        assertEquals(400, put("alice", "/api/plugins/tool/memory_write", "{\"enabled\":\"no\"}").statusCode());
        assertEquals(400, put("alice", "/api/plugins/tool/memory_write", "not json").statusCode());
    }

    @Test
    void anonymousCallersGetNothing() throws Exception {
        assertEquals(401, server.get(null, "/api/plugins").statusCode());
        assertEquals(401, put(null, "/api/plugins/tool/memory_write", "{\"enabled\":false}").statusCode());
        assertEquals(401, put(null, "/api/plugins/local/local-workspace", "{\"enabled\":false}").statusCode());
    }

    private void ask(String user, String session) throws Exception {
        String body = "{\"requestId\":\"r-" + session + "\",\"mode\":\"prompt\",\"text\":\"hello from " + user + "\"}";
        assertEquals(200, server.post(user, "/api/sessions/" + session + "/submissions", body).statusCode());
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            if (server.get(user, "/api/sessions/" + session + "/runs").body().contains("COMPLETED")) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("run of " + session + " did not finish");
    }

    private ToolSpec spec(String user, String tool) {
        return prompts.stream().filter(prompt -> prompt.messages().toString().contains("hello from " + user))
                .findFirst().orElseThrow().tools().stream().filter(spec -> spec.name().equals(tool)).findFirst().orElseThrow();
    }
}
