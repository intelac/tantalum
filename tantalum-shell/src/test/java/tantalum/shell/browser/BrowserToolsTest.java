package tantalum.shell.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.PlaywrightException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tantalum.shell.json.JsonText;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.Workspace;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/** 需求 2.2、2.6、W2、W7：11 个工具的名字、说明、参数；不合格的网址不起浏览器。真实浏览器的行为在 IT 里测。 */
class BrowserToolsTest {

    private final AtomicInteger launches = new AtomicInteger();
    private final Browsers browsers = new Browsers(new Browsers.Settings(2, Duration.ofMinutes(10), true), null, null,
            Clock.systemUTC(), () -> {
                launches.incrementAndGet();
                throw new Browsers.Unavailable("no browser in unit tests");
            }, Duration.ofHours(1));

    @AfterEach
    void stop() {
        browsers.close();
    }

    private Tool tool(String name) {
        return BrowserTools.all(browsers).stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    private static ToolContext context() throws Exception {
        return new ToolContext(new Principal("alice"), new SessionId("s1"), new Workspace(Path.of(System.getProperty("java.io.tmpdir"))));
    }

    /** 需求 2.2：正好 W7 的 11 个；没有上传、下载、拖放、执行任意代码。 */
    @Test
    void exactlyTheElevenToolsOfW7AndNothingDangerous() {
        List<String> names = BrowserTools.all(browsers).stream().map(Tool::name).toList();
        assertEquals(List.of("browser_navigate", "browser_navigate_back", "browser_snapshot", "browser_click", "browser_type",
                "browser_press_key", "browser_select_option", "browser_wait_for", "browser_take_screenshot", "browser_tabs",
                "browser_evaluate"), names);
        assertEquals(11, new HashSet<>(names).size());
        for (String name : names) {
            assertFalse(name.matches(".*(upload|download|file|drag|drop|run_code|install|pdf).*"), name);
        }
        for (Tool tool : BrowserTools.all(browsers)) {
            assertEquals("browser", tool.server());
            assertEquals(64, tool.serverConcurrency());
            assertFalse(tool.isConcurrencySafe(new Json.Obj(Map.of())), "the browser has state: calls of one turn run in order");
        }
    }

    /** 需求 2.6、W1：搜索用 Bing 的网址格式，写明 Google 常限流；空闲时间照配置写。 */
    @Test
    void navigateSaysHowToSearch() {
        String description = tool("browser_navigate").description();
        assertTrue(description.contains("`https://www.bing.com/search?q=<words>`"), description);
        assertTrue(description.contains("Google often shows headless browsers a rate-limit page"), description);
        assertTrue(description.contains("after 10 minutes without use"), description);
        for (String name : List.of("browser_click", "browser_type", "browser_select_option")) {
            assertTrue(tool(name).description().contains("Refs come from the latest browser_snapshot or action result."), name);
        }
    }

    /** 每个 schema 是对象，必填的参数都在 properties 里；键的顺序固定（工具定义不随启动变化，提示缓存才稳定）。 */
    @Test
    void everySchemaIsAnObjectWithDeclaredRequiredProperties() {
        for (Tool tool : BrowserTools.all(browsers)) {
            Json.Obj schema = assertInstanceOf(Json.Obj.class, tool.schema(), tool.name());
            assertEquals(new Json.Str("object"), schema.fields().get("type"), tool.name());
            Json.Obj properties = assertInstanceOf(Json.Obj.class, schema.fields().get("properties"), tool.name());
            if (schema.fields().get("required") instanceof Json.Arr required) {
                for (Json name : required.items()) {
                    assertTrue(properties.fields().containsKey(((Json.Str) name).value()), tool.name() + " requires " + name);
                }
            }
            for (Json property : properties.fields().values()) {
                assertTrue(((Json.Obj) property).fields().containsKey("type"), tool.name());
            }
        }
        assertEquals("{\"type\":\"object\",\"properties\":{\"ref\":{\"type\":\"string\",\"description\":\"The element ref, like e5.\"},"
                        + "\"text\":{\"type\":\"string\",\"description\":\"The text to type.\"},\"submit\":{\"type\":\"boolean\","
                        + "\"description\":\"Press Enter after typing.\"}},\"required\":[\"ref\",\"text\"]}",
                JsonText.toText(tool("browser_type").schema()));
    }

    /** W2：file:、data:、javascript: 和带密码的网址不合格，浏览器根本不起。 */
    @Test
    void badUrlsAreRefusedBeforeABrowserStarts() throws Exception {
        for (String url : List.of("file:///etc/passwd", "data:text/html,<p>x</p>", "javascript:alert(1)", "chrome://settings",
                "https://user:pass@example.com/")) {
            ToolOutput output = tool("browser_navigate").run(context(), new Json.Obj(Map.of("url", new Json.Str(url))));
            assertTrue(output.error(), url);
            assertTrue(output.text().endsWith("allowed."), output.text());
        }
        ToolOutput missing = tool("browser_wait_for").run(context(), new Json.Obj(Map.of()));
        assertEquals("Give text, textGone or seconds.", missing.text());
        assertEquals(0, launches.get());
    }

    /** 浏览器起不来时，消息照原样成为错误结果。 */
    @Test
    void anUnavailableBrowserIsAnErrorResult() throws Exception {
        ToolOutput output = tool("browser_snapshot").run(context(), new Json.Obj(Map.of()));
        assertTrue(output.error());
        assertEquals("no browser in unit tests", output.text());
    }

    /** 设计“{@code BrowserTool}”的错误写法：只给第一行，不给调用日志。 */
    @Test
    void playwrightErrorsBecomeShortSentences() {
        assertEquals("Timed out after 30 s waiting for the page.", BrowserTool.describe(new PlaywrightException(
                "Timeout 30000ms exceeded.\nCall log:\n  - navigating to \"https://slow.example/\"")));
        assertEquals("The page or one of its redirects was blocked by this server's network rules, or the site could not be reached.",
                BrowserTool.describe(new PlaywrightException("Error: net::ERR_TUNNEL_CONNECTION_FAILED at https://x.example/\nCall log:")));
        assertEquals("This URL starts a file download; downloads are not allowed.",
                BrowserTool.describe(new PlaywrightException("Error: Download is starting\nCall log:\n  - navigating")));
        assertEquals("Element is not visible", BrowserTool.describe(new PlaywrightException("Error: Element is not visible\nsecret call log")));
        // 驱动送回的结构化错误
        assertEquals("This URL starts a file download; downloads are not allowed.", BrowserTool.describe(new PlaywrightException(
                "Error {\n  message='Download is starting\n  name='Error\n  stack='Error: Download is starting\n    at x.js:1\n}")));
    }
}
