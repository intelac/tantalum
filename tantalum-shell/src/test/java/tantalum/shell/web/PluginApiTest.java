package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** core-tools 需求 7：插件页的接口，每个用户只管自己的。 */
class PluginApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 一个必须开启的策略钩子：用户不能关。 */
    static final class Quota implements KernelHook {
        @Override public String name() { return "dailyQuota"; }
        @Override public String description() { return "daily limit"; }
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

    @Test
    void listsBuiltInToolsAndHookPlugins() throws Exception {
        JsonNode body = plugins("alice");
        for (String tool : List.of("read_file", "grep", "list_dir", "find_files", "write_file", "edit_file", "memory_write")) {
            assertTrue(named(body.get("tools"), tool).get("enabled").asBoolean(), tool);
        }
        assertEquals(0, body.get("mcp").size());
        JsonNode quota = named(body.get("plugins"), "dailyQuota");
        assertTrue(quota.get("mandatory").asBoolean());
        assertEquals("daily limit", quota.get("description").asString());
        assertEquals("[\"beforeModelCall\"]", quota.get("points").toString());
        assertFalse(named(body.get("plugins"), "notes").get("mandatory").asBoolean());
    }

    /** 7.6：开关只影响自己；关掉的工具从下一次组装开始不给这个用户。 */
    @Test
    void switchesBelongToTheUserWhoSetsThem() throws Exception {
        HttpResponse<String> off = put("alice", "/api/plugins/tool/write_file", "{\"enabled\":false}");
        assertEquals(200, off.statusCode(), off.body());
        assertFalse(named(plugins("alice").get("tools"), "write_file").get("enabled").asBoolean());
        assertTrue(named(plugins("bob").get("tools"), "write_file").get("enabled").asBoolean());
        assertEquals(200, put("alice", "/api/plugins/plugin/notes", "{\"enabled\":false}").statusCode());
        assertTrue(named(plugins("bob").get("plugins"), "notes").get("enabled").asBoolean());

        ask("alice", "a1");
        ask("bob", "b1");
        // 工具列表是全集（会话中途换工具时它不能变，core-tools C14）；alice 关掉的 write_file 标 deferred，模型看不见
        assertTrue(spec("alice", "write_file").deferred());
        assertFalse(spec("alice", "read_file").deferred());
        assertFalse(spec("bob", "write_file").deferred());
    }

    @Test
    void mandatoryPluginsAndUnknownNamesAreRefused() throws Exception {
        assertEquals(400, put("alice", "/api/plugins/plugin/dailyQuota", "{\"enabled\":false}").statusCode());
        assertTrue(named(plugins("alice").get("plugins"), "dailyQuota").get("enabled").asBoolean());
        assertEquals(404, put("alice", "/api/plugins/tool/nope", "{\"enabled\":false}").statusCode());
        assertEquals(404, put("alice", "/api/plugins/mcp/nope", "{\"enabled\":false}").statusCode());
        assertEquals(404, put("alice", "/api/plugins/hook/notes", "{\"enabled\":false}").statusCode());
        assertEquals(400, put("alice", "/api/plugins/tool/write_file", "{\"enabled\":\"no\"}").statusCode());
        assertEquals(400, put("alice", "/api/plugins/tool/write_file", "not json").statusCode());
    }

    @Test
    void anonymousCallersGetNothing() throws Exception {
        assertEquals(401, server.get(null, "/api/plugins").statusCode());
        assertEquals(401, put(null, "/api/plugins/tool/write_file", "{\"enabled\":false}").statusCode());
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
