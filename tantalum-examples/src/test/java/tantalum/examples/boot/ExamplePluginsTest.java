package tantalum.examples.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.web.TestServer;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.Stop;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.KernelState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A1、A2：示例插件由自动配置注册，{@code tantalum.examples.enabled=true} 才注册；插件页上列出，策略插件对用户默认关，
 * 管理员可以用 {@code tantalum.hooks.required} 强制开启；各插件的设置来自 {@code tantalum.examples.*}。
 * 打包后的 jar 经 {@code loader.path} 加载由 {@code ExamplesJarIT} 测。
 */
class ExamplePluginsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private TestServer server(String... args) {
        return new TestServer(temp.resolve("data"), temp.resolve("ws"),
                (prompt, chunks) -> new ModelOutcome.Replied(java.util.List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null), args);
    }

    /** 插件名 → [enabled, mandatory]。 */
    private static Map<String, String> plugins(TestServer server, String user) throws Exception {
        Map<String, String> plugins = new LinkedHashMap<>();
        for (JsonNode plugin : JSON.readTree(server.get(user, "/api/plugins").body()).get("plugins")) {
            plugins.put(plugin.get("name").asString(), (plugin.get("enabled").asBoolean() ? "on" : "off")
                    + (plugin.get("mandatory").asBoolean() ? ",required" : ""));
        }
        return plugins;
    }

    private static java.net.http.HttpResponse<String> put(TestServer server, String user, String path, String body) throws Exception {
        return server.http.send(server.request(user, path).header("content-type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void withoutTheSwitchNothingIsRegistered() throws Exception {
        try (TestServer server = server()) {
            assertEquals(Map.of(), plugins(server, "alice"));
        }
    }

    @Test
    void theExamplesAreListedAndPoliciesStartOff() throws Exception {
        try (TestServer server = server("--tantalum.examples.enabled=true")) {
            assertEquals(Map.of("citationCheck", "on", "dailyQuota", "off", "pinnedContext", "on", "readOnly", "off",
                    "toolApproval", "off", "truncateOutput", "on"), plugins(server, "alice"),
                    "providerBackoff is left out without a status url");
            assertEquals(200, put(server, "alice", "/api/plugins/plugin/readOnly", "{\"enabled\":true}").statusCode());
            assertEquals("on", plugins(server, "alice").get("readOnly"));
            assertEquals("off", plugins(server, "bob").get("readOnly"), "turning it on is per user");
        }
    }

    @Test
    void settingsReachThePluginsAndAnAdminCanRequireAPolicy() throws Exception {
        try (TestServer server = server("--tantalum.examples.enabled=true", "--tantalum.examples.read-only.tools=read_file",
                "--tantalum.examples.provider-backoff.status-url=http://127.0.0.1:1/status", "--tantalum.examples.truncate-output.enabled=false",
                "--tantalum.hooks.required=dailyQuota")) {
            Map<String, String> plugins = plugins(server, "alice");
            assertEquals("on,required", plugins.get("dailyQuota"));
            assertEquals("on", plugins.get("providerBackoff"));
            assertTrue(!plugins.containsKey("truncateOutput"), plugins.toString());
            assertEquals(400, put(server, "alice", "/api/plugins/plugin/dailyQuota", "{\"enabled\":false}").statusCode());

            KernelHook readOnly = server.context.getBean("exampleReadOnlyHook", KernelHook.class);
            HookContext context = HookContext.of(new RunRef(new Principal("alice"), new SessionId("s"), new RunId("r")), Instant.EPOCH);
            assertSame(Allow.PLAIN, readOnly.beforeToolCall(KernelState.empty(),
                    context.withCall(new ToolCallBlock("c", "read_file", "{}", new Json.Obj(Map.of())))));
            assertInstanceOf(Stop.class, readOnly.beforeToolCall(KernelState.empty(),
                    context.withCall(new ToolCallBlock("c", "grep", "{}", new Json.Obj(Map.of())))));
        }
    }

}
