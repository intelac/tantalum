package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.plugin.Configurable;
import tantalum.shell.plugin.DailyLimit;
import tantalum.shell.plugin.Param;
import tantalum.shell.plugin.PluginInfo;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Params;
import tantalum.value.json.Json;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.KernelState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** plugin-settings 设计 §8（任务 21–24）：用户改自己的参数，管理员定默认、锁定、按人指定，用户列表带今天的用量。 */
class PluginParamsApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SECRET = "s3cret-9f2c7a41";

    /** 内置工具 search：一个普通参数、一个密码。 */
    static final class Search implements Tool, Configurable {
        @Override public String name() { return "search"; }
        @Override public String description() { return "Search."; }
        @Override public Json schema() { return new Json.Obj(Map.of("type", new Json.Str("object"))); }
        @Override public List<Param> params() { return List.of(Param.number("maxChars", 500).range(100, 10000), Param.secret("apiKey")); }
        @Override public ToolOutput run(ToolContext context, Json arguments) { return ToolOutput.ok("ok"); }
    }

    /** 插件 quota：额度只归管理员，默认关。 */
    static final class Quota implements KernelHook, PluginInfo, Configurable, DailyLimit {
        @Override public OptionalLong dailyTokens(Params params) { return OptionalLong.of(params.number("tokens", 1000)); }
        @Override public java.util.Optional<String> limitParam() { return java.util.Optional.of("tokens"); }
        @Override public String name() { return "quotaHook"; }
        @Override public String plugin() { return "quota"; }
        @Override public boolean defaultEnabled() { return false; }
        @Override public List<Param> params() { return List.of(Param.number("tokens", 1000).atLeast(1).adminOnly()); }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) { return Allow.PLAIN; }
    }

    /** 代码里定了必须开启。 */
    static final class Audit implements KernelHook, PluginInfo {
        @Override public String name() { return "auditHook"; }
        @Override public String plugin() { return "audit"; }
        @Override public boolean mandatory() { return true; }
        @Override public HookResult beforeRunEnd(KernelState state, HookContext context) { return Allow.PLAIN; }
    }

    @TempDir
    Path temp;

    private TestServer server;

    @BeforeEach
    void start() throws Exception {
        ModelClient model = (prompt, chunks) -> new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m",
                new TokenUsage(100, 20, 30, 5));
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model, context -> {
            context.registerBean("search", Tool.class, Search::new);
            context.registerBean("quota", KernelHook.class, Quota::new);
            context.registerBean("audit", KernelHook.class, Audit::new);
        });
        server.users().add("root", TestServer.PASSWORD, true);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> send(String user, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = server.request(user, path).header("content-type", "application/json");
        return server.http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static void refused(int status, String code, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"code\":\"" + code + "\""), response.body());
    }

    private static JsonNode named(JsonNode list, String field, String name) {
        for (JsonNode item : list) {
            if (item.get(field).asString().equals(name)) {
                return item;
            }
        }
        throw new AssertionError(name + " not in " + list);
    }

    private JsonNode tool(String user) throws Exception {
        return named(ok(send(user, "GET", "/api/plugins", null)).get("tools"), "name", "search");
    }

    private JsonNode plugin(String user, String name) throws Exception {
        return named(ok(send(user, "GET", "/api/plugins", null)).get("plugins"), "name", name);
    }

    private static JsonNode param(JsonNode item, String key) {
        return named(item.get("params"), "key", key);
    }

    @Test
    void theListCarriesDefaultsLocksAndParams() throws Exception {
        JsonNode search = tool("alice");
        assertTrue(search.get("enabled").asBoolean());
        assertTrue(search.get("byDefault").asBoolean());
        assertFalse(search.get("changed").asBoolean());
        assertFalse(search.has("lock"));
        assertEquals("{\"key\":\"maxChars\",\"type\":\"number\",\"label\":\"\",\"hint\":\"\",\"owner\":\"user\",\"min\":100,\"max\":10000,"
                + "\"value\":500,\"source\":\"builtin\",\"editable\":true}", param(search, "maxChars").toString());
        JsonNode key = param(search, "apiKey");
        assertEquals("secret", key.get("type").asString());
        assertFalse(key.get("set").asBoolean());
        assertFalse(key.has("value"), "a secret never has a value: " + key);

        JsonNode quota = plugin("alice", "quota");
        assertFalse(quota.get("enabled").asBoolean());
        assertFalse(quota.get("byDefault").asBoolean());
        assertEquals("rule", quota.get("group").asString());
        assertEquals("admin", param(quota, "tokens").get("owner").asString());
        assertFalse(param(quota, "tokens").get("editable").asBoolean());
        assertEquals("{\"source\":\"code\",\"reason\":\"\"}", plugin("alice", "audit").get("lock").toString());
        assertEquals(0, plugin("alice", "audit").get("params").size());
    }

    @Test
    void aUserSavesResetsAndClearsTheirOwn() throws Exception {
        HttpResponse<String> saved = send("alice", "PUT", "/api/plugins/tool/search/params",
                "{\"values\":{\"maxChars\":200,\"apiKey\":\"" + SECRET + "\"}}");
        JsonNode view = ok(saved);
        assertFalse(saved.body().contains(SECRET), "the answer has no secret");
        assertEquals("search", view.get("name").asString());
        assertEquals(200, param(view, "maxChars").get("value").asInt());
        assertEquals("own", param(view, "maxChars").get("source").asString());
        assertTrue(param(view, "apiKey").get("set").asBoolean());
        assertFalse(send("alice", "GET", "/api/plugins", null).body().contains(SECRET));
        // 别人看不到也不受影响
        assertEquals(500, param(tool("bob"), "maxChars").get("value").asInt());
        assertFalse(param(tool("bob"), "apiKey").get("set").asBoolean());

        // 整份检查：一个不合格，另一个也不存
        HttpResponse<String> range = send("alice", "PUT", "/api/plugins/tool/search/params",
                "{\"values\":{\"apiKey\":\"another-one\",\"maxChars\":5}}");
        refused(400, "param/invalid", range);
        assertTrue(range.body().contains("\"reason\":\"range\"") && range.body().contains("\"key\":\"maxChars\""), range.body());
        assertFalse(range.body().contains("another-one"), range.body());
        assertEquals(200, param(tool("alice"), "maxChars").get("value").asInt());
        HttpResponse<String> unknown = send("alice", "PUT", "/api/plugins/tool/search/params", "{\"values\":{\"nope\":1}}");
        refused(400, "param/invalid", unknown);
        assertTrue(unknown.body().contains("\"reason\":\"unknown\""), unknown.body());
        refused(403, "param/forbidden", send("alice", "PUT", "/api/plugins/plugin/quota/params", "{\"values\":{\"tokens\":999999}}"));
        assertEquals(1000, param(plugin("alice", "quota"), "tokens").get("value").asInt());
        // 请求体不对：不回显里面的东西
        HttpResponse<String> bad = send("alice", "PUT", "/api/plugins/tool/search/params", "{\"apiKey\":\"" + SECRET + "\"}");
        refused(400, "request/invalid", bad);
        assertFalse(bad.body().contains(SECRET), bad.body());
        refused(404, "plugin/not-found", send("alice", "PUT", "/api/plugins/tool/nope/params", "{\"values\":{}}"));
        refused(404, "plugin/not-found", send("alice", "PUT", "/api/plugins/nope/search/params", "{\"values\":{}}"));

        // 参数恢复默认：普通参数回去，密码不动
        JsonNode reset = ok(send("alice", "DELETE", "/api/plugins/tool/search/params", null));
        assertEquals(500, param(reset, "maxChars").get("value").asInt());
        assertEquals("builtin", param(reset, "maxChars").get("source").asString());
        assertTrue(param(reset, "apiKey").get("set").asBoolean());
        refused(400, "param/invalid", send("alice", "DELETE", "/api/plugins/tool/search/params/maxChars", null));
        assertFalse(param(ok(send("alice", "DELETE", "/api/plugins/tool/search/params/apiKey", null)), "apiKey").get("set").asBoolean());

        // 开关恢复默认
        ok(send("alice", "PUT", "/api/plugins/tool/search", "{\"enabled\":false}"));
        assertTrue(tool("alice").get("changed").asBoolean());
        ok(send("alice", "DELETE", "/api/plugins/tool/search", null));
        assertFalse(tool("alice").get("changed").asBoolean());
        assertTrue(tool("alice").get("enabled").asBoolean());
    }

    @Test
    void anAdminSetsDefaultsAndLocks() throws Exception {
        assertEquals(403, send("alice", "GET", "/api/admin/plugins", null).statusCode());
        assertEquals(403, send("alice", "PUT", "/api/admin/plugins/tool/search", "{\"enabled\":false}").statusCode());
        JsonNode items = ok(send("root", "GET", "/api/admin/plugins", null)).get("items");
        JsonNode search = named(items, "name", "search");
        assertEquals("tool", search.get("kind").asString());
        assertTrue(search.get("builtin").asBoolean());
        assertFalse(search.has("byAdmin"));
        assertFalse(search.has("lock"));
        assertEquals(List.of("maxChars"), search.get("params").valueStream().map(item -> item.get("key").asString()).toList(),
                "secrets have no default");
        assertEquals("code", named(items, "name", "audit").get("lock").get("source").asString());

        // 默认关并锁定
        JsonNode locked = ok(send("root", "PUT", "/api/admin/plugins/tool/search", "{\"enabled\":false,\"locked\":true,\"reason\":\"audit\"}"));
        assertFalse(locked.get("byAdmin").asBoolean());
        assertEquals("{\"source\":\"admin\",\"reason\":\"audit\"}", locked.get("lock").toString());
        assertFalse(tool("alice").get("enabled").asBoolean());
        assertEquals("{\"source\":\"admin\",\"reason\":\"audit\"}", tool("alice").get("lock").toString());
        HttpResponse<String> refusedSwitch = send("alice", "PUT", "/api/plugins/tool/search", "{\"enabled\":true}");
        refused(403, "plugin/locked", refusedSwitch);
        JsonNode why = JSON.readTree(refusedSwitch.body()).get("params");
        assertEquals(Map.of("name", "search", "source", "admin", "reason", "audit"), JSON.convertValue(why, Map.class), refusedSwitch.body());
        // 锁着：普通参数不能改，密码照样能填
        refused(403, "plugin/locked", send("alice", "PUT", "/api/plugins/tool/search/params", "{\"values\":{\"maxChars\":200}}"));
        assertTrue(param(ok(send("alice", "PUT", "/api/plugins/tool/search/params", "{\"values\":{\"apiKey\":\"" + SECRET + "\"}}")),
                "apiKey").get("set").asBoolean());

        // 只改参数的默认值：开关和锁不动
        JsonNode withParam = ok(send("root", "PUT", "/api/admin/plugins/tool/search", "{\"params\":{\"maxChars\":300}}"));
        assertEquals(300, param(withParam, "maxChars").get("value").asInt());
        assertEquals("audit", withParam.get("lock").get("reason").asString());
        assertEquals("admin", param(tool("bob"), "maxChars").get("source").asString());
        assertEquals(300, param(tool("bob"), "maxChars").get("value").asInt());
        // 只改原因：默认和锁不动
        JsonNode reason = ok(send("root", "PUT", "/api/admin/plugins/tool/search", "{\"reason\":\"policy\"}"));
        assertEquals("{\"source\":\"admin\",\"reason\":\"policy\"}", reason.get("lock").toString());
        assertFalse(reason.get("byAdmin").asBoolean());

        // 清掉
        JsonNode cleared = ok(send("root", "PUT", "/api/admin/plugins/tool/search",
                "{\"enabled\":null,\"locked\":false,\"params\":{\"maxChars\":null}}"));
        assertFalse(cleared.has("byAdmin"));
        assertFalse(cleared.has("lock"));
        assertEquals("builtin", param(cleared, "maxChars").get("source").asString());
        assertTrue(tool("alice").get("enabled").asBoolean());

        // 拒绝
        HttpResponse<String> code = send("root", "PUT", "/api/admin/plugins/plugin/audit", "{\"enabled\":false}");
        refused(403, "plugin/locked", code);
        assertTrue(code.body().contains("\"source\":\"code\""), code.body());
        refused(400, "param/invalid", send("root", "PUT", "/api/admin/plugins/tool/search", "{\"enabled\":false,\"params\":{\"maxChars\":5}}"));
        assertFalse(named(ok(send("root", "GET", "/api/admin/plugins", null)).get("items"), "name", "search").has("byAdmin"),
                "a refused request changes nothing");
        refused(400, "request/invalid", send("root", "PUT", "/api/admin/plugins/tool/search", "{\"reason\":\"" + "x".repeat(501) + "\"}"));
        refused(400, "request/invalid", send("root", "PUT", "/api/admin/plugins/tool/search", "{\"enabled\":\"yes\"}"));
        refused(400, "request/invalid", send("root", "PUT", "/api/admin/plugins/tool/search", "{}"));
        refused(404, "plugin/not-found", send("root", "PUT", "/api/admin/plugins/tool/nope", "{\"enabled\":false}"));
        refused(404, "plugin/not-found", send("root", "PUT", "/api/admin/plugins/local/files", "{\"enabled\":false}"));
    }

    @Test
    void anAdminAssignsAdminOnlyParamsPerUser() throws Exception {
        server.request("alice", "/api/plugins");
        assertEquals(403, send("alice", "GET", "/api/admin/users/alice/params", null).statusCode());
        JsonNode before = ok(send("root", "GET", "/api/admin/users/alice/params", null));
        assertEquals("alice", before.get("user").asString());
        JsonNode quota = named(before.get("items"), "name", "quota");
        assertEquals("plugin", quota.get("kind").asString());
        assertEquals(1000, param(quota, "tokens").get("value").asInt());
        assertEquals("builtin", param(quota, "tokens").get("source").asString());

        JsonNode after = ok(send("root", "PUT", "/api/admin/users/alice/params/plugin/quota", "{\"values\":{\"tokens\":50}}"));
        assertEquals(50, param(named(after.get("items"), "name", "quota"), "tokens").get("value").asInt());
        assertEquals("assigned", param(named(after.get("items"), "name", "quota"), "tokens").get("source").asString());
        assertEquals(50, param(plugin("alice", "quota"), "tokens").get("value").asInt());
        assertEquals(1000, param(plugin("bob", "quota"), "tokens").get("value").asInt());

        refused(400, "param/invalid", send("root", "PUT", "/api/admin/users/alice/params/plugin/quota", "{\"values\":{\"tokens\":0}}"));
        refused(403, "param/forbidden", send("root", "PUT", "/api/admin/users/alice/params/tool/search", "{\"values\":{\"maxChars\":200}}"));
        refused(404, "user/not-found", send("root", "GET", "/api/admin/users/nobody/params", null));
        refused(404, "user/not-found", send("root", "PUT", "/api/admin/users/nobody/params/plugin/quota", "{\"values\":{\"tokens\":50}}"));
        refused(404, "plugin/not-found", send("root", "PUT", "/api/admin/users/alice/params/plugin/nope", "{\"values\":{\"tokens\":50}}"));

        JsonNode cleared = ok(send("root", "PUT", "/api/admin/users/alice/params/plugin/quota", "{\"values\":{\"tokens\":null}}"));
        assertEquals(1000, param(named(cleared.get("items"), "name", "quota"), "tokens").get("value").asInt());
    }

    /** 任务 24：用户列表带每人今天的用量，合计和每日额度插件算的一样（读入的含缓存，加输出的）。 */
    @Test
    void theUserListCarriesTodaysUsage() throws Exception {
        assertEquals(200, server.post("alice", "/api/sessions/s1/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"hi\"}")
                .statusCode());
        JsonNode alice = null;
        Instant deadline = Instant.now().plusSeconds(20);
        // 用量从索引读，索引是批量写的
        while (Instant.now().isBefore(deadline)) {
            alice = named(ok(send("root", "GET", "/api/users", null)).get("users"), "name", "alice").get("usageToday");
            if (alice.get("total").asLong() > 0) {
                break;
            }
            Thread.sleep(10);
        }
        assertEquals("{\"input\":100,\"output\":20,\"cacheRead\":30,\"cacheWrite\":5,\"total\":155}", String.valueOf(alice));
        assertEquals("{\"input\":0,\"output\":0,\"cacheRead\":0,\"cacheWrite\":0,\"total\":0}",
                named(ok(send("root", "GET", "/api/users", null)).get("users"), "name", "root").get("usageToday").toString());
    }

    /**
     * input-area 需求 6.1–6.3：登录的人查自己今天的用量和生效的上限。上限只在报它的插件开着时才有，跟着管理员给这个人指定的值变；
     * 用量和运维页是同一个数，只回自己的。
     */
    @Test
    void anyoneSeesTheirOwnUsageAndLimitForToday() throws Exception {
        JsonNode fresh = ok(send("alice", "GET", "/api/usage/today", null));
        assertEquals(0, fresh.get("used").asLong());
        assertFalse(fresh.has("limit"), "the quota plugin is off by default");
        assertTrue(fresh.get("day").asString().matches("\\d{4}-\\d{2}-\\d{2}"), fresh.toString());

        ok(send("alice", "PUT", "/api/plugins/plugin/quota", "{\"enabled\":true}"));
        assertEquals(1000, ok(send("alice", "GET", "/api/usage/today", null)).get("limit").asLong());
        ok(send("root", "PUT", "/api/admin/users/alice/params/plugin/quota", "{\"values\":{\"tokens\":500}}"));
        assertEquals(500, ok(send("alice", "GET", "/api/usage/today", null)).get("limit").asLong());
        assertFalse(ok(send("bob", "GET", "/api/usage/today", null)).has("limit"));

        assertEquals(200, server.post("alice", "/api/sessions/s1/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"hi\"}")
                .statusCode());
        JsonNode today = null;
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            today = ok(send("alice", "GET", "/api/usage/today", null));
            if (today.get("used").asLong() > 0) {
                break;
            }
            Thread.sleep(10);
        }
        assertEquals(List.of(100L, 20L, 30L, 5L, 155L, 500L), List.of(today.get("input").asLong(), today.get("output").asLong(),
                today.get("cacheRead").asLong(), today.get("cacheWrite").asLong(), today.get("used").asLong(), today.get("limit").asLong()));
        assertEquals(0, ok(send("bob", "GET", "/api/usage/today", null)).get("used").asLong());
        assertEquals(401, server.http.send(HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.port() + "/api/usage/today")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    // ---- qagent-align 需求 4、5：运维表格 ----

    private JsonNode row(JsonNode list, String name) {
        for (JsonNode user : list.get("users")) {
            if (user.get("name").asString().equals(name)) {
                return user;
            }
        }
        throw new AssertionError("no user " + name + " in " + list);
    }

    private void ask(String user, String session, String request) throws Exception {
        assertEquals(200, send(user, "POST", "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"" + request + "\",\"mode\":\"prompt\",\"text\":\"hi\"}").statusCode());
        for (int i = 0; i < 1000; i++) {
            JsonNode runs = JSON.readTree(send(user, "GET", "/api/sessions/" + session + "/runs", null).body()).get("runs");
            if (runs != null && runs.size() > 0 && runs.get(runs.size() - 1).has("endReason")) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the run did not end");
    }

    @Test
    void theOpsTableShowsSessionsRunsAndEachPersonsLimit() throws Exception {
        ask("alice", "s1", "r1");
        ask("alice", "s2", "r2");
        ask("bob", "s9", "r9");
        assertEquals(200, send("alice", "PUT", "/api/sessions/s2/archived", "{\"archived\":true}").statusCode());
        ok(send("alice", "PUT", "/api/plugins/plugin/quota", "{\"enabled\":true}"));

        JsonNode list = ok(send("root", "GET", "/api/users", null));
        // 顶上：能直接改的那个上限是哪个插件的哪个参数、所有人的默认、范围
        assertEquals("{\"plugin\":\"quota\",\"key\":\"tokens\",\"value\":1000,\"min\":1}", list.get("dailyLimit").toString());
        assertFalse(list.get("weighted").asBoolean());
        assertEquals("[]", list.get("weights").toString());

        JsonNode alice = row(list, "alice");
        assertEquals(1, alice.get("sessions").asInt(), "the archived session does not count: " + alice);
        assertEquals(0, alice.get("running").asInt());
        assertEquals("{\"tokens\":1000,\"own\":false}", alice.get("limit").toString());
        // bob 没开每日额度：没有上限
        JsonNode bob = row(list, "bob");
        assertEquals(1, bob.get("sessions").asInt());
        assertEquals("{\"off\":true}", bob.get("limit").toString());
        assertEquals(0, row(list, "root").get("sessions").asInt());

        // 单独设置：只有这个人变
        ok(send("root", "PUT", "/api/admin/users/alice/params/plugin/quota", "{\"values\":{\"tokens\":50}}"));
        list = ok(send("root", "GET", "/api/users", null));
        assertEquals("{\"tokens\":50,\"own\":true}", row(list, "alice").get("limit").toString());
        assertEquals(1000, list.get("dailyLimit").get("value").asInt());
        // 数字不合参数的规矩：拒绝，不保存
        assertEquals(400, send("root", "PUT", "/api/admin/users/alice/params/plugin/quota", "{\"values\":{\"tokens\":0}}").statusCode());
        assertEquals("{\"tokens\":50,\"own\":true}", row(ok(send("root", "GET", "/api/users", null)), "alice").get("limit").toString());

        // 改默认：等于改“插件默认值”里那个参数；单独设的不受影响
        ok(send("root", "PUT", "/api/admin/plugins/plugin/quota", "{\"params\":{\"tokens\":7000}}"));
        list = ok(send("root", "GET", "/api/users", null));
        assertEquals(7000, list.get("dailyLimit").get("value").asInt());
        assertEquals("{\"tokens\":50,\"own\":true}", row(list, "alice").get("limit").toString());

        // 恢复默认
        ok(send("root", "PUT", "/api/admin/users/alice/params/plugin/quota", "{\"values\":{\"tokens\":null}}"));
        assertEquals("{\"tokens\":7000,\"own\":false}", row(ok(send("root", "GET", "/api/users", null)), "alice").get("limit").toString());

        // 这些数只有管理员能取
        assertEquals(403, send("alice", "GET", "/api/users", null).statusCode());
    }

    /** 需求 4.3：此刻在跑或在等回答的运行数。 */
    @Test
    void aRunThatIsStillGoingIsCounted() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        server.close();
        ModelClient slow = (prompt, chunks) -> {
            try {
                release.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            return new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);
        };
        server = new TestServer(temp.resolve("data2"), temp.resolve("ws"), slow);
        server.users().add("root", TestServer.PASSWORD, true);
        try {
            assertEquals(200, send("alice", "POST", "/api/sessions/s1/submissions",
                    "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"hi\"}").statusCode());
            int running = 0;
            for (int i = 0; i < 500 && running == 0; i++) {
                Thread.sleep(10);
                running = row(ok(send("root", "GET", "/api/users", null)), "alice").get("running").asInt();
            }
            assertEquals(1, running);
            JsonNode list = ok(send("root", "GET", "/api/users", null));
            // 没有任何插件报每日上限：每个人都没有 limit，顶上也没有 dailyLimit
            assertFalse(row(list, "alice").has("limit"), list.toString());
            assertFalse(list.has("dailyLimit"));
        } finally {
            release.countDown();
        }
    }
}
