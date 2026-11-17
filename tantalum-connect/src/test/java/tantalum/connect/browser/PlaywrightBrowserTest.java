package tantalum.connect.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.local.browser.PageText;
import tantalum.local.tool.LocalResult;
import tantalum.local.wire.Wire;
import tantalum.value.json.Json;

/**
 * 翻译到 Playwright MCP（connector-jar 需求 7.3）：样本是 2026-11-14 在本机跑 {@code @playwright/mcp@0.0.85} 录下的原样输出。
 * 不起浏览器；真的跑一遍见 {@code PlaywrightMcpIT}。
 */
class PlaywrightBrowserTest {

    static final String NAVIGATED = """
            ### Ran Playwright code
            ```js
            await page.goto('https://example.com');
            ```
            ### Page
            - Page URL: https://example.com/
            - Page Title: Example Domain
            ### Snapshot
            - [Snapshot](../../../var/folders/x/page-2026-11-13T15-50-42-209Z.yml)""";
    static final String SNAPSHOT = """
            ### Page
            - Page URL: https://example.com/
            - Page Title: Example Domain
            ### Snapshot
            ```yaml
            - generic [active] [ref=e1]:
              - heading "Example Domain" [level=1] [ref=e3]
              - link "Learn more" [ref=e6]:
                - /url: https://iana.org/domains/example
            ```""";
    static final String TABS = """
            ### Result
            - 0: [Example Domain](https://example.com/)
            - 1: (current) [Example Domain](https://example.org/)""";
    static final String MISSING_REF = """
            ### Error
            Error: Ref e999 not found in the current page snapshot. Try capturing new snapshot.""";
    static final String EVALUATED = """
            ### Result
            {"color":"g","n":2}
            ### Ran Playwright code
            ```js
            await page.evaluate('() => document.title');
            ```""";

    /** 记下发给 Playwright MCP 的调用，按工具名给录下的回答。 */
    private final List<String> sent = new ArrayList<>();

    private PlaywrightBrowser browser(Map<String, LocalResult> answers) {
        return PlaywrightBrowserFixture.of((tool, arguments) -> {
            sent.add(tool + " " + Wire.text(arguments));
            return answers.getOrDefault(tool, LocalResult.ok("### Result\nok"));
        });
    }

    private static Json args(Object... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            fields.put((String) pairs[i], value instanceof Json json ? json : value instanceof Integer n
                    ? new Json.Num(java.math.BigDecimal.valueOf(n)) : value instanceof Boolean b ? new Json.Bool(b) : new Json.Str((String) value));
        }
        return new Json.Obj(fields);
    }

    @Test
    void navigateThenSnapshotLooksLikeBefore() throws Exception {
        PlaywrightBrowser browser = browser(Map.of("browser_navigate", LocalResult.ok(NAVIGATED), "browser_snapshot", LocalResult.ok(SNAPSHOT),
                "browser_tabs", LocalResult.ok(TABS)));
        LocalResult result = browser.call("s", "browser_navigate", args("url", "https://example.com"));
        assertEquals(List.of("browser_navigate {\"url\":\"https://example.com\"}", "browser_snapshot {}", "browser_tabs {\"action\":\"list\"}"), sent);
        assertEquals(PageText.UNTRUSTED + "\nPage: Example Domain\nURL: https://example.com/\nTab 2 of 2\n\n"
                + "- generic [active] [ref=e1]:\n  - heading \"Example Domain\" [level=1] [ref=e3]\n  - link \"Learn more\" [ref=e6]:\n"
                + "    - /url: https://iana.org/domains/example", result.text());
    }

    @Test
    void refsBecomeTargetsAndAMissingRefSaysWhatItSaidBefore() throws Exception {
        PlaywrightBrowser browser = browser(Map.of("browser_click", LocalResult.error(MISSING_REF)));
        LocalResult result = browser.call("s", "browser_click", args("ref", "e999"));
        assertEquals("browser_click {\"target\":\"e999\",\"element\":\"e999\"}", sent.getFirst());
        assertTrue(result.error());
        assertEquals("Element ref e999 is not on the current page; call browser_snapshot for fresh refs.", result.text());
    }

    @Test
    void tabsAreNumberedFromOneForTheModel() throws Exception {
        PlaywrightBrowser browser = browser(Map.of("browser_tabs", LocalResult.ok(TABS), "browser_snapshot", LocalResult.ok(SNAPSHOT)));
        LocalResult listed = browser.call("s", "browser_tabs", args("action", "list"));
        assertEquals(PageText.UNTRUSTED + "\nTabs (* is the current tab of this conversation; select one with browser_tabs action=select index=N):\n"
                + "  1. Example Domain - https://example.com/\n* 2. Example Domain - https://example.org/", listed.text());
        sent.clear();
        browser.call("s", "browser_tabs", args("action", "select", "index", 1));
        assertEquals("browser_tabs {\"action\":\"select\",\"index\":0}", sent.get(1));
        assertTrue(browser.call("s", "browser_tabs", args("action", "select", "index", 5)).error());
    }

    @Test
    void waitTypeAndEvaluateArguments() throws Exception {
        PlaywrightBrowser browser = browser(Map.of("browser_evaluate", LocalResult.ok(EVALUATED), "browser_snapshot", LocalResult.ok(SNAPSHOT),
                "browser_tabs", LocalResult.ok(TABS)));
        browser.call("s", "browser_wait_for", args("seconds", 2, "text", "Done"));
        assertEquals("browser_wait_for {\"time\":2,\"text\":\"Done\"}", sent.getFirst());
        sent.clear();
        browser.call("s", "browser_type", args("ref", "e5", "text", "hi", "submit", true));
        assertEquals("browser_type {\"target\":\"e5\",\"element\":\"e5\",\"text\":\"hi\",\"submit\":true}", sent.getFirst());
        LocalResult evaluated = browser.call("s", "browser_evaluate", args("expression", "() => 1"));
        assertEquals(PageText.UNTRUSTED + "\n{\"color\":\"g\",\"n\":2}", evaluated.text());
    }

    @Test
    void builtInArguments() {
        List<String> args = PlaywrightBrowser.args("msedge", java.nio.file.Path.of("/Apps/Edge"), java.nio.file.Path.of("/p"),
                java.nio.file.Path.of("/o"), true);
        // 路径按本机的写法（Windows 上是 \\p）
        assertEquals(List.of("-y", PlaywrightBrowser.PACKAGE, "--browser", "msedge", "--user-data-dir", java.nio.file.Path.of("/p").toString(),
                "--output-dir", java.nio.file.Path.of("/o").toString(), "--image-responses", "allow", "--executable-path",
                java.nio.file.Path.of("/Apps/Edge").toString(), "--headless"), args);
    }

    /** QAgent 问题 26：Playwright MCP 写进 --output-dir 的快照、截图文件每次调用后清掉，不只增不删。 */
    @Test
    void theOutputDirectoryIsEmptiedAfterEachCall(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path stale = java.nio.file.Files.writeString(dir.resolve("page-old.yml"), "x");
        PlaywrightBrowser browser = new PlaywrightBrowser((tool, arguments) -> {
            try {
                java.nio.file.Files.writeString(dir.resolve("page-" + tool + ".yml"), "snapshot");
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
            return tool.equals("browser_snapshot") ? LocalResult.ok(SNAPSHOT) : LocalResult.ok(TABS);
        }, dir);
        assertTrue(java.nio.file.Files.notExists(stale), "cleared at start");
        browser.call("s", "browser_snapshot", args());
        try (var left = java.nio.file.Files.list(dir)) {
            assertEquals(0, left.count());
        }
    }

    /** QAgent 问题 71：模型给的元素说明照转给 Playwright MCP。 */
    @Test
    void theElementDescriptionIsPassedOn() throws Exception {
        PlaywrightBrowser browser = browser(Map.of("browser_snapshot", LocalResult.ok(SNAPSHOT), "browser_tabs", LocalResult.ok(TABS)));
        browser.call("s", "browser_click", args("ref", "e6", "element", "Learn more link"));
        assertEquals("browser_click {\"target\":\"e6\",\"element\":\"Learn more link\"}", sent.getFirst());
    }
}
