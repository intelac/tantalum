package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
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
import tantalum.shell.panel.Panel;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.text.LocalText;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** plugin-panel 需求 1–3：面板文件的响应头和路径检查，列表跟着插件开关，只读查看也能列。 */
class PanelControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Panel PROBE = new Panel() {
        @Override public String name() { return "probe"; }
        @Override public LocalText title() { return LocalText.of("Probe", "探针", "プローブ"); }
        @Override public String base() { return "tantalum/panels/probe/"; }
        @Override public String icon() { return "activity"; }
    };

    @TempDir
    Path temp;

    private TestServer server;

    private final ModelClient model = (prompt, chunks) ->
            new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("workspace")), model,
                context -> context.registerBean("probePanel", Panel.class, () -> PROBE));
        if (server.users().status("root").isEmpty()) {
            server.users().add("root", TestServer.PASSWORD, true);
        }
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> anonymous(String path) throws Exception {
        return server.get(null, path);
    }

    @Test
    void panelFilesAreStaticWithAStrictPolicyAndNoLogin() throws Exception {
        HttpResponse<String> page = anonymous("/panels/probe/index.html");
        assertEquals(200, page.statusCode(), page.body());
        assertTrue(page.headers().firstValue("content-type").orElse("").startsWith("text/html"));
        assertEquals(PanelController.CSP, page.headers().firstValue("content-security-policy").orElse(""));
        assertTrue(page.headers().firstValue("content-security-policy").orElse("").contains("connect-src 'none'"));
        assertEquals("nosniff", page.headers().firstValue("x-content-type-options").orElse(""));
        assertEquals("*", page.headers().firstValue("access-control-allow-origin").orElse(""));
        assertEquals("SAMEORIGIN", page.headers().firstValue("x-frame-options").orElse(""));
        assertTrue(page.body().contains("probe.js"));
        HttpResponse<String> script = anonymous("/panels/probe/probe.js");
        assertEquals(200, script.statusCode());
        assertTrue(script.headers().firstValue("content-type").orElse("").startsWith("text/javascript"));
    }

    @Test
    void unknownPanelsTypesAndTraversalAre404() throws Exception {
        assertEquals(404, anonymous("/panels/nope/index.html").statusCode());
        assertEquals(404, anonymous("/panels/probe/missing.js").statusCode());
        assertEquals(404, anonymous("/panels/probe/notes.txt").statusCode(), "only known file types");
        assertTrue(anonymous("/panels/probe/%2e%2e/%2e%2e/application.properties").statusCode() >= 400);
        assertTrue(anonymous("/panels/probe/a//b.js").statusCode() >= 400);
        assertTrue(PanelController.safe("css/a.css"));
        assertTrue(!PanelController.safe("../x.js") && !PanelController.safe("a/./b.js") && !PanelController.safe("a\\b.js"));
    }

    @Test
    void theListFollowsThePluginSwitchAndWorksForViewAs() throws Exception {
        JsonNode panels = JSON.readTree(server.get("alice", "/api/panels").body()).get("panels");
        assertEquals(1, panels.size());
        assertEquals("/panels/probe/index.html", panels.get(0).get("url").asString());
        assertEquals("activity", panels.get(0).get("icon").asString());
        assertEquals(401, anonymous("/api/panels").statusCode());

        HttpResponse<String> off = server.http.send(server.request("alice", "/api/plugins/plugin/probe").header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"enabled\":false}")).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(off.statusCode() < 300, off.body());
        assertEquals(0, JSON.readTree(server.get("alice", "/api/panels").body()).get("panels").size());
        assertEquals(0, JSON.readTree(server.get("root", "/api/panels?viewAs=alice").body()).get("panels").size());
        assertEquals(1, JSON.readTree(server.get("root", "/api/panels").body()).get("panels").size());
    }
}
