package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.access.WebAccess;
import tantalum.shell.boot.Engine;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.Workspace;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/** web-tools 任务 11、19：联网的配置接进服务器（W6、W9、W10，需求 4.4、5.3、2.8）。不访问外网，也不起 Chromium。 */
@Timeout(60)
class WebToolsWiringTest {

    private static final Principal ALICE = new Principal("alice");

    @TempDir
    Path temp;

    private final CompletableFuture<String> system = new CompletableFuture<>();
    private final ModelClient model = (prompt, chunks) -> {
        system.complete(prompt.system());
        return new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);
    };

    private TestServer server(String... extra) throws IOException {
        Files.createDirectories(temp.resolve("workspace"));
        return new TestServer(temp.resolve("data"), temp.resolve("workspace"), model, extra);
    }

    private static List<String> names(Engine engine) {
        return engine.tools().stream().map(Tool::name).filter(name -> name.equals("web_fetch") || name.startsWith("browser_")).toList();
    }

    /**
     * 默认 web_fetch 注册在服务器上；11 个浏览器工具也注册着，但它们是转发到用户电脑的本机工具（local-browser），服务器自己不起浏览器。
     * system 里有“## Web content”那段（W9）。
     */
    @Test
    void byDefaultWebFetchIsRegisteredAndTheSystemSaysWebContentIsUntrusted() throws Exception {
        try (TestServer server = server()) {
            Engine engine = server.context.getBean(Engine.class);
            List<String> names = names(engine);
            assertTrue(names.contains("web_fetch"), names.toString());
            List<Tool> browser = engine.tools().stream().filter(tool -> tool.name().startsWith("browser_")).toList();
            assertEquals(11, browser.size(), names.toString());
            for (Tool tool : browser) {
                assertEquals(tantalum.shell.local.LocalTool.class, tool.getClass(), tool.name() + " runs on the user's computer");
                assertEquals("local-workspace", tool.server());
            }
            assertEquals(200, engine.intake().submit(ALICE, new SessionId("s1"), new RequestId("r1"), "prompt", "hi").status());
            String text = system.get(30, TimeUnit.SECONDS);
            assertTrue(text.contains(WebAccess.NOTE), text);
        }
    }

    /** 需求 4.4：服务器随机端口起来后，web_fetch 访问它自己被拦，开关关着也一样（端口来自 WebServerInitializedEvent）。 */
    @Test
    void webFetchCannotReachTheServerItself() throws Exception {
        try (TestServer server = server("--tantalum.web.block-private-network=false")) {
            Tool fetch = server.context.getBean(Engine.class).tools().stream().filter(tool -> tool.name().equals("web_fetch"))
                    .findFirst().orElseThrow();
            ToolOutput output = fetch.run(new ToolContext(ALICE, new SessionId("s1"), new Workspace(temp)),
                    new Json.Obj(Map.of("url", new Json.Str("http://127.0.0.1:" + server.port() + "/api/config"))));
            assertTrue(output.error());
            assertEquals("Blocked: 127.0.0.1:" + server.port() + " is the Tantalum server itself.", output.text());
        }
    }

    /**
     * 需求 5.3：管理员关掉抓网页就不注册 web_fetch，也不起出口；插件页照样有这一组。local-browser 需求 8.2：以前关浏览器的配置还写着时
     * 照常启动、不起作用。“网页内容不可信”那段总是在：浏览器工具在用户电脑上，不归这个配置管。
     */
    @Test
    void whatTheAdminTurnsOffIsNotRegistered() throws Exception {
        try (TestServer server = server("--tantalum.web.fetch.enabled=false", "--tantalum.web.browser.enabled=false",
                "--tantalum.web.browser.max-open=0")) {
            Engine engine = server.context.getBean(Engine.class);
            assertFalse(names(engine).contains("web_fetch"));
            assertEquals(11, names(engine).size(), "the browser tools are local tools; the old browser.enabled=false does nothing");
            assertEquals(Map.of("web-fetch", false), engine.webAccess().admin());
            assertTrue(engine.egress().isEmpty(), "no egress when nothing on the server uses it");
            assertEquals(200, engine.intake().submit(ALICE, new SessionId("s1"), new RequestId("r1"), "prompt", "hi").status());
            assertTrue(system.get(30, TimeUnit.SECONDS).contains("## Web content"));
        }
        try (TestServer server = server("--tantalum.web.browser.enabled=false")) {
            Engine engine = server.context.getBean(Engine.class);
            assertTrue(names(engine).contains("web_fetch"));
            assertTrue(engine.egress().isPresent(), "web_fetch still needs the egress");
        }
    }

    /** 需求 2.8：关机后出口的端口不再接受连接。 */
    @Test
    void theEgressStopsListeningAtShutdown() throws Exception {
        int port;
        try (TestServer server = server()) {
            port = server.context.getBean(Engine.class).egress().orElseThrow().port();
            try (Socket open = new Socket(InetAddress.getLoopbackAddress(), port)) {
                assertTrue(open.isConnected());
            }
        }
        assertThrows(IOException.class, () -> new Socket(InetAddress.getLoopbackAddress(), port).close());
    }
}
