package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.local.TestComputer;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 插件页的“技能”“知识库”接口和输入框的技能菜单（skills-wiki 任务 20；需求 2.1–2.5、5.1、5.5、6.1、8.1–8.7）：
 * 每个用户自己的目录和开关；清单当场向电脑要；各种“现在列不出来”的状态各有一个名字。
 */
class SkillApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private TestServer server;
    private Path mac;

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"),
                (prompt, chunks) -> new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null));
        mac = Files.createDirectories(temp.resolve("mac")).toRealPath();
        skill("pdf-tools", "description: Convert PDF files to text.");
        skill("by-hand", "description: Only when asked.\ndisable-model-invocation: true");
        skill("model-only", "description: Not for users.\nuser-invocable: false");
        skill("no-description", "license: MIT");
        Files.createDirectories(mac.resolve("wiki/ops"));
        Files.writeString(mac.resolve("wiki/ops/rollback.md"), "# Rolling back\n");
        Files.writeString(mac.resolve("wiki/notes.md"), "notes");
        Files.writeString(mac.resolve("wiki/index.md"), "Start here.");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private void skill(String name, String fields) throws Exception {
        Path folder = Files.createDirectories(mac.resolve("skills").resolve(name));
        Files.writeString(folder.resolve("SKILL.md"), "---\nname: " + name + "\n" + fields + "\n---\nbody\n");
    }

    private HttpResponse<String> send(String user, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = server.request(user, path).header("content-type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return server.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String user, String path) throws Exception {
        HttpResponse<String> response = server.get(user, path);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private JsonNode put(String user, String path, String body) throws Exception {
        HttpResponse<String> response = send(user, "PUT", path, body);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static String dirBody(Path dir) {
        return "{\"dir\":" + JSON.writeValueAsString(dir.toString()) + "}";
    }

    private static List<String> names(JsonNode items) {
        List<String> names = new ArrayList<>();
        items.forEach(item -> names.add(item.get("name").asString()));
        return names;
    }

    @Test
    void anonymousCallersGetNothing() throws Exception {
        for (String path : List.of("/api/local/skills", "/api/local/wiki", "/api/skills")) {
            assertEquals(401, server.get(null, path).statusCode(), path);
        }
        assertEquals(401, send(null, "PUT", "/api/local/skills", "{\"dir\":\"/x\"}").statusCode());
        assertEquals(401, send(null, "PUT", "/api/local/skills/pdf-tools", "{\"enabled\":false}").statusCode());
        assertEquals(401, send(null, "PUT", "/api/local/wiki", "{\"dir\":\"/x\"}").statusCode());
    }

    /** 默认什么都没填：两项都是 unset，菜单是空的（需求 8.2）。 */
    @Test
    void nothingIsFilledInByDefault() throws Exception {
        JsonNode skills = get("alice", "/api/local/skills");
        assertEquals("", skills.get("dir").asString());
        assertEquals("unset", skills.get("state").asString());
        assertEquals(0, skills.get("items").size());
        assertEquals("unset", get("alice", "/api/local/wiki").get("state").asString());
        assertEquals(0, get("alice", "/api/skills").get("skills").size());
    }

    @Test
    void theSkillsOfADirectoryAreListedWithTheirProblems() throws Exception {
        try (TestComputer ignored = server.connectComputer("alice", mac, false)) {
            JsonNode view = put("alice", "/api/local/skills", dirBody(mac.resolve("skills")));
            assertEquals("ok", view.get("state").asString(), view.toString());
            assertEquals(mac.resolve("skills").toString(), view.get("dir").asString());
            assertEquals(mac.resolve("skills").toString(), view.get("path").asString());
            assertEquals(List.of("by-hand", "model-only", "pdf-tools"), names(view.get("items")));
            JsonNode byHand = view.get("items").get(0);
            assertFalse(byHand.get("model").asBoolean());
            assertTrue(byHand.get("user").asBoolean());
            assertTrue(byHand.get("enabled").asBoolean());
            assertEquals("Only when asked.", byHand.get("description").asString());
            assertEquals(1, view.get("problems").size(), view.toString());
            assertEquals("no-description", view.get("problems").get(0).get("folder").asString());
            assertEquals("description-missing", view.get("problems").get(0).get("code").asString());

            assertEquals(view, get("alice", "/api/local/skills"), "the same listing on the next look");
            assertEquals("unset", get("bob", "/api/local/skills").get("state").asString(), "bob has his own setting");
        }
    }

    /** 关掉一个技能：列表里还在但标成关的，菜单里没有；再打开就回来（需求 8.3）。 */
    @Test
    void oneSkillCanBeTurnedOffAndBackOn() throws Exception {
        try (TestComputer ignored = server.connectComputer("alice", mac, false)) {
            put("alice", "/api/local/skills", dirBody(mac.resolve("skills")));
            JsonNode menu = get("alice", "/api/skills").get("skills");
            assertEquals(List.of("by-hand", "pdf-tools"), names(menu), "what the user may name; model-only is left out");
            assertTrue(menu.get(0).get("manualOnly").asBoolean());
            assertFalse(menu.get(1).get("manualOnly").asBoolean());
            assertEquals("Convert PDF files to text.", menu.get(1).get("description").asString());

            JsonNode off = put("alice", "/api/local/skills/pdf-tools", "{\"enabled\":false}");
            assertFalse(off.get("enabled").asBoolean());
            JsonNode view = get("alice", "/api/local/skills");
            assertFalse(view.get("items").get(2).get("enabled").asBoolean());
            assertTrue(view.get("items").get(0).get("enabled").asBoolean());
            assertEquals(List.of("by-hand"), names(get("alice", "/api/skills").get("skills")));

            put("alice", "/api/local/skills/pdf-tools", "{\"enabled\":true}");
            assertEquals(List.of("by-hand", "pdf-tools"), names(get("alice", "/api/skills").get("skills")));

            assertEquals(400, send("alice", "PUT", "/api/local/skills/Bad%20Name", "{\"enabled\":false}").statusCode());
            assertEquals(400, send("alice", "PUT", "/api/local/skills/pdf-tools", "{\"enabled\":\"no\"}").statusCode());
        }
    }

    @Test
    void theWikiShowsHowManyPagesAndWhetherThereIsAnIndex() throws Exception {
        try (TestComputer ignored = server.connectComputer("alice", mac, false)) {
            JsonNode view = put("alice", "/api/local/wiki", dirBody(mac.resolve("wiki")));
            assertEquals("ok", view.get("state").asString(), view.toString());
            assertEquals(mac.resolve("wiki").toString(), view.get("path").asString());
            assertEquals(2, view.get("pages").asInt());
            assertEquals(2, view.get("total").asInt());
            assertTrue(view.get("index").asBoolean());
            assertFalse(view.get("truncated").asBoolean());
            assertFalse(view.toString().contains("Start here."), "the index text itself is not sent to the page");
        }
    }

    /** 写法不对的目录不存，回 local/dir-invalid；空串清掉（需求 2.1）。 */
    @Test
    void aDirectoryMustBeAbsoluteAndAnEmptyOneClearsIt() throws Exception {
        put("alice", "/api/local/skills", "{\"dir\":\"~/skills\"}");
        for (String path : List.of("/api/local/skills", "/api/local/wiki")) {
            HttpResponse<String> relative = send("alice", "PUT", path, "{\"dir\":\"skills\"}");
            assertEquals(400, relative.statusCode(), relative.body());
            assertEquals("local/dir-invalid", JSON.readTree(relative.body()).get("code").asString());
            assertEquals(400, send("alice", "PUT", path, "{\"dir\":7}").statusCode());
            assertEquals(400, send("alice", "PUT", path, "not json").statusCode());
        }
        assertEquals("~/skills", get("alice", "/api/local/skills").get("dir").asString(), "a refused value leaves the old one");
        JsonNode cleared = put("alice", "/api/local/skills", "{\"dir\":\"\"}");
        assertEquals("", cleared.get("dir").asString());
        assertEquals("unset", cleared.get("state").asString());
    }

    /** 每种“列不出来”各有一个状态：开关关着、没连上、连接程序太旧、目录不存在、目录不在允许的范围里（需求 2.2、2.3、2.5、8.1）。 */
    @Test
    void eachReasonNothingIsListedHasItsOwnState() throws Exception {
        put("alice", "/api/local/skills", dirBody(mac.resolve("skills")));
        put("alice", "/api/local/wiki", dirBody(mac.resolve("wiki")));
        assertEquals("offline", get("alice", "/api/local/skills").get("state").asString());
        assertEquals("offline", get("alice", "/api/local/wiki").get("state").asString());
        assertEquals(0, get("alice", "/api/skills").get("skills").size());

        try (TestComputer ignored = server.connectComputer("alice", mac, false, 3)) {
            assertEquals("old", get("alice", "/api/local/skills").get("state").asString());
            assertEquals("old", get("alice", "/api/local/wiki").get("state").asString());
            assertEquals(0, get("alice", "/api/skills").get("skills").size());
        }
        try (TestComputer computer = server.connectComputer("alice", mac, false)) {
            assertEquals(200, send("alice", "PUT", "/api/plugins/local/skills", "{\"enabled\":false}").statusCode());
            assertEquals("off", get("alice", "/api/local/skills").get("state").asString());
            assertEquals("ok", get("alice", "/api/local/wiki").get("state").asString(), "the wiki has its own switch");
            assertEquals(0, get("alice", "/api/skills").get("skills").size());
            assertEquals(200, send("alice", "PUT", "/api/plugins/local/skills", "{\"enabled\":true}").statusCode());
            assertEquals(200, send("alice", "PUT", "/api/plugins/local/local-workspace", "{\"enabled\":false}").statusCode());
            assertEquals("off", get("alice", "/api/local/skills").get("state").asString());
            assertEquals("off", get("alice", "/api/local/wiki").get("state").asString());
            assertEquals(200, send("alice", "PUT", "/api/plugins/local/local-workspace", "{\"enabled\":true}").statusCode());

            assertEquals("missing", put("alice", "/api/local/skills", dirBody(mac.resolve("not-yet"))).get("state").asString());
            JsonNode outside = put("alice", "/api/local/skills", dirBody(temp.resolve("elsewhere")));
            assertEquals("outside", outside.get("state").asString());
            assertFalse(outside.has("path"), "nothing about the directory is revealed");
            assertEquals("outside", put("alice", "/api/local/wiki", dirBody(temp.resolve("elsewhere"))).get("state").asString());

            put("alice", "/api/local/skills", dirBody(mac.resolve("skills")));
            computer.ignoreContextRequests();
            assertEquals("late", get("alice", "/api/local/skills").get("state").asString());
        }
    }
}
