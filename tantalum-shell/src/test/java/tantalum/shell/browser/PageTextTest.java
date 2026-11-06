package tantalum.shell.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.fetch.WebFetchTool;

/** 需求 2.7、W9：浏览器结果的第一行是那句话（和 web_fetch 一字不差）；快照最多 50,000 字符并写明原长度。 */
class PageTextTest {

    @Test
    void everyResultStartsWithTheUntrustedLine() {
        assertEquals(WebFetchTool.UNTRUSTED, PageText.UNTRUSTED);
        for (String text : List.of(PageText.snapshot("T", "https://a.example/", 1, 1, "- button \"Go\" [ref=e2]"),
                PageText.tabs(List.of(new PageText.Tab("A", "https://a.example/")), 1), PageText.evaluated("42"),
                PageText.screenshot("https://a.example/"))) {
            assertEquals(PageText.UNTRUSTED, text.lines().findFirst().orElseThrow());
        }
    }

    @Test
    void snapshotHeaderNamesThePageAndTab() {
        assertEquals(PageText.UNTRUSTED + "\nPage: Bing\nURL: https://www.bing.com/search?q=x\nTab 2 of 3\n\n- link \"x\" [ref=e5]",
                PageText.snapshot("Bing", "https://www.bing.com/search?q=x", 2, 3, "- link \"x\" [ref=e5]"));
        assertTrue(PageText.snapshot("", "about:blank", 1, 1, "").contains("Page: (no title)"));
    }

    @Test
    void longSnapshotsKeepTheFirst50000CharactersAndSaySo() {
        String snapshot = "x".repeat(81_234);
        String text = PageText.snapshot("T", "https://a.example/", 1, 1, snapshot);
        assertTrue(text.contains("x".repeat(50_000) + "\n\n(Snapshot truncated: 81,234 characters, showing the first 50,000. "
                + "Use browser_evaluate to read a specific part.)"), text.substring(text.length() - 200));
        assertTrue(!text.contains("x".repeat(50_001)));
        String json = PageText.evaluated("\"" + "y".repeat(60_000) + "\"");
        assertTrue(json.endsWith("(Result truncated: 60,002 characters, showing the first 50,000. Return a smaller value.)"), json.substring(json.length() - 120));
    }

    @Test
    void tabListMarksTheCurrentTab() {
        assertEquals(PageText.UNTRUSTED + "\nTabs (* is the current tab of this conversation; select one with browser_tabs action=select "
                        + "index=N):\n  1. A - https://a.example/\n* 2. (no title) - about:blank",
                PageText.tabs(List.of(new PageText.Tab("A", "https://a.example/"), new PageText.Tab("", "about:blank")), 2));
    }
}
