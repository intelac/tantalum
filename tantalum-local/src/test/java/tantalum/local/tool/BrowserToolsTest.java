package tantalum.local.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.sandbox.Sandbox;
import tantalum.local.sandbox.Unavailable;
import tantalum.value.json.Json;

/**
 * local-browser 需求 4（原 web-tools 需求 2.2、2.6、W2、W7）：11 个工具的名字、说明、参数和浏览器还在服务器上时一样；
 * 参数不合格的调用不交给浏览器。真实浏览器的行为在连接程序的 IT 里测。
 */
class BrowserToolsTest {

    @TempDir
    Path dir;

    /** 记下交到浏览器的调用。 */
    private final List<String> calls = new ArrayList<>();
    private final Browser browser = (session, tool, arguments) -> {
        calls.add(session + " " + tool);
        return LocalResult.ok("done");
    };

    private static LocalTool tool(String name) {
        return BrowserTools.all().stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    private LocalContext context(Browser browser) throws Exception {
        return new LocalContext("s1", new AllowedDirs(List.of(dir)), new Observations(),
                Sandbox.unavailable(Unavailable.of(Unavailable.Code.SHELL_NOT_ALLOWED)), null, browser);
    }

    private static Json args(String... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put(pairs[i], new Json.Str(pairs[i + 1]));
        }
        return new Json.Obj(fields);
    }

    /** 需求 4.1、4.8：正好那 11 个；没有上传、下载、拖放、执行任意代码。 */
    @Test
    void exactlyTheElevenToolsAndNothingDangerous() {
        List<String> names = BrowserTools.all().stream().map(LocalTool::name).toList();
        assertEquals(List.of("browser_navigate", "browser_navigate_back", "browser_snapshot", "browser_click", "browser_type",
                "browser_press_key", "browser_select_option", "browser_wait_for", "browser_take_screenshot", "browser_tabs",
                "browser_evaluate"), names);
        assertEquals(11, new HashSet<>(names).size());
        for (LocalTool tool : BrowserTools.all()) {
            assertFalse(tool.name().matches(".*(upload|download|file|drag|drop|run_code|install|pdf).*"), tool.name());
            assertEquals(LocalTool.Kind.BROWSER, tool.kind());
            assertFalse(tool.isConcurrencySafe(new Json.Obj(Map.of())), "the browser has state: calls of one turn run in order");
        }
        assertEquals(BrowserTools.all(), BrowserTools.all(), "the definitions are fixed objects");
    }

    /** 需求 4.2：说明写的是用户电脑上的浏览器，不再提服务器、无头、空闲几分钟；搜索的办法照旧写着。 */
    @Test
    void navigateDescribesABrowserOnTheUsersComputerAndHowToSearch() {
        String description = tool("browser_navigate").description();
        assertTrue(description.startsWith("Open a URL in a browser on the user's computer."), description);
        assertTrue(description.contains("logins made in it are kept between conversations"), description);
        assertTrue(description.contains("visible to the user"), description);
        assertTrue(description.contains("`https://www.bing.com/search?q=<words>`"), description);
        assertTrue(description.contains("Google often shows automated browsers a rate-limit page"), description);
        for (LocalTool tool : BrowserTools.all()) {
            assertFalse(tool.description().matches("(?s).*(on the server|headless|minutes without use).*"), tool.name());
        }
        for (String name : List.of("browser_click", "browser_type", "browser_select_option")) {
            assertTrue(tool(name).description().contains("Refs come from the latest browser_snapshot or action result."), name);
        }
    }

    /** 需求 4.1、4.3：参数逐字钉住，键的顺序固定（工具定义不变，提示缓存才稳定）。和搬家前服务器上的 schema 一字不差。 */
    @Test
    void schemasAreWhatTheServerSideBrowserHad() {
        Map<String, String> schemas = BrowserTools.all().stream()
                .collect(Collectors.toMap(LocalTool::name, tool -> text(tool.schema()), (a, b) -> a, LinkedHashMap::new));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("browser_navigate", "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\","
                + "\"description\":\"The http or https URL to open.\",\"maxLength\":2048}},\"required\":[\"url\"]}");
        expected.put("browser_navigate_back", "{\"type\":\"object\",\"properties\":{}}");
        expected.put("browser_snapshot", "{\"type\":\"object\",\"properties\":{}}");
        expected.put("browser_click", "{\"type\":\"object\",\"properties\":{\"ref\":{\"type\":\"string\","
                + "\"description\":\"The element ref, like e5.\"}},\"required\":[\"ref\"]}");
        expected.put("browser_type", "{\"type\":\"object\",\"properties\":{\"ref\":{\"type\":\"string\",\"description\":\"The element ref, like e5.\"},"
                + "\"text\":{\"type\":\"string\",\"description\":\"The text to type.\"},\"submit\":{\"type\":\"boolean\","
                + "\"description\":\"Press Enter after typing.\"}},\"required\":[\"ref\",\"text\"]}");
        expected.put("browser_press_key", "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\","
                + "\"description\":\"The key, in Playwright key names.\"}},\"required\":[\"key\"]}");
        expected.put("browser_select_option", "{\"type\":\"object\",\"properties\":{\"ref\":{\"type\":\"string\","
                + "\"description\":\"The element ref, like e5.\"},\"values\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"minItems\":1,"
                + "\"description\":\"The values or labels of the options to choose.\"}},\"required\":[\"ref\",\"values\"]}");
        expected.put("browser_wait_for", "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\","
                + "\"description\":\"Wait until this text is visible.\"},\"textGone\":{\"type\":\"string\","
                + "\"description\":\"Wait until this text is no longer visible.\"},\"seconds\":{\"type\":\"number\","
                + "\"description\":\"Wait this many seconds.\",\"minimum\":0,\"maximum\":30}}}");
        expected.put("browser_take_screenshot", "{\"type\":\"object\",\"properties\":{\"fullPage\":{\"type\":\"boolean\","
                + "\"description\":\"Capture the whole scrollable page instead of the visible part.\"}}}");
        expected.put("browser_tabs", "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"list\",\"select\"],"
                + "\"description\":\"list the tabs, or select one by index.\"},\"index\":{\"type\":\"integer\",\"minimum\":1,"
                + "\"description\":\"The tab to select, 1 is the first tab.\"}},\"required\":[\"action\"]}");
        expected.put("browser_evaluate", "{\"type\":\"object\",\"properties\":{\"expression\":{\"type\":\"string\","
                + "\"description\":\"A JavaScript expression or a function like () => document.title.\"}},\"required\":[\"expression\"]}");
        assertEquals(expected, schemas);
    }

    /** 需求 6.1：file:、data:、javascript:、chrome: 和带密码的网址不合格，根本不交给浏览器。 */
    @Test
    void badUrlsNeverReachTheBrowser() throws Exception {
        for (String url : List.of("file:///etc/passwd", "data:text/html,<p>x</p>", "javascript:alert(1)", "chrome://settings",
                "about:blank", "https://user:pass@example.com/")) {
            LocalResult result = tool("browser_navigate").run(context(browser), args("url", url));
            assertTrue(result.error(), url);
            assertTrue(result.text().endsWith("allowed."), result.text());
        }
        assertEquals("Missing argument: url", tool("browser_navigate").run(context(browser), args()).text());
        assertEquals(List.of(), calls);
    }

    /** 需求 4.7 和各工具的必填参数：格式不对的当场回错误，不启动浏览器。 */
    @Test
    void malformedArgumentsNeverReachTheBrowser() throws Exception {
        assertEquals("Give text, textGone or seconds.", tool("browser_wait_for").run(context(browser), args()).text());
        assertEquals("seconds must be between 0 and 30.", tool("browser_wait_for").run(context(browser),
                new Json.Obj(Map.of("seconds", new Json.Num(new java.math.BigDecimal("31"))))).text());
        assertEquals("action must be list or select.", tool("browser_tabs").run(context(browser), args("action", "close")).text());
        assertEquals("Give the index of the tab to select (1 is the first tab).",
                tool("browser_tabs").run(context(browser), args("action", "select")).text());
        for (String name : List.of("browser_click", "browser_type", "browser_select_option")) {
            LocalResult result = tool(name).run(context(browser), args("ref", "#login", "text", "x"));
            assertEquals("Element ref #login is not on the current page; call browser_snapshot for fresh refs.", result.text(), name);
            assertTrue(result.error());
        }
        assertEquals("Missing argument: text", tool("browser_type").run(context(browser), args("ref", "e5")).text());
        assertEquals("Missing argument: values", tool("browser_select_option").run(context(browser), args("ref", "e5")).text());
        assertEquals("Missing argument: key", tool("browser_press_key").run(context(browser), args()).text());
        assertEquals("Missing argument: expression", tool("browser_evaluate").run(context(browser), args()).text());
        assertEquals(List.of(), calls);
    }

    /** 合格的调用带着会话和工具名交给浏览器，结果原样返回；框架里的编号（f1e5）也算合格。 */
    @Test
    void wellFormedCallsGoToTheBrowserOfThisComputer() throws Exception {
        assertEquals("done", tool("browser_navigate").run(context(browser), args("url", "https://example.com/a?q=1")).text());
        assertEquals("done", tool("browser_click").run(context(browser), args("ref", "f1e5")).text());
        assertEquals("done", tool("browser_snapshot").run(context(browser), args()).text());
        assertEquals(List.of("s1 browser_navigate", "s1 browser_click", "s1 browser_snapshot"), calls);
    }

    /** 需求 1.1：这次调用没给浏览器（没有 --allow-browser）时一律是那句拒绝。 */
    @Test
    void withoutABrowserEveryToolIsRefused() throws Exception {
        LocalResult result = tool("browser_snapshot").run(context(null), args());
        assertTrue(result.error());
        assertEquals("This computer does not allow the browser.", result.text());
        assertInstanceOf(Browser.class, context(null).browser());
    }

    /** 需求 5.9 的另一面：浏览器正常地慢（起浏览器、等页面）时服务器不先放弃——超时至少 100 秒，服务器配得更长就用更长的。 */
    @Test
    void theServerWaitsLongEnoughForASlowPage() {
        Json none = new Json.Obj(Map.of());
        for (LocalTool tool : BrowserTools.all()) {
            assertEquals(java.time.Duration.ofSeconds(100), tool.timeout(none, java.time.Duration.ofSeconds(60)), tool.name());
            assertEquals(java.time.Duration.ofSeconds(300), tool.timeout(none, java.time.Duration.ofSeconds(300)), tool.name());
        }
    }

    /** 按键的顺序写成 JSON 文字，数字用原文。 */
    private static String text(Json json) {
        return switch (json) {
            case Json.Obj object -> object.fields().entrySet().stream()
                    .map(entry -> "\"" + entry.getKey() + "\":" + text(entry.getValue())).collect(Collectors.joining(",", "{", "}"));
            case Json.Arr array -> array.items().stream().map(BrowserToolsTest::text).collect(Collectors.joining(",", "[", "]"));
            case Json.Str string -> "\"" + string.value() + "\"";
            case Json.Num number -> number.value().toPlainString();
            case Json.Bool flag -> String.valueOf(flag.value());
            case Json.Null ignored -> "null";
        };
    }
}
