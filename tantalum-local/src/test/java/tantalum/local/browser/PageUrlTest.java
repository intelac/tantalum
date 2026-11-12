package tantalum.local.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * local-browser 需求 6.1：浏览器只开 http 和 https。规则的细节（国际化域名、下划线主机、长度）由服务器那边的
 * {@code WebUrlTest} 逐条测，它走的是同一份代码。
 */
class PageUrlTest {

    @Test
    void onlyWebSchemesOpen() {
        assertEquals("https://example.com/a?q=1", PageUrl.check(" https://example.com/a?q=1 ").toString());
        assertEquals("http://127.0.0.1:8080/", PageUrl.check("http://127.0.0.1:8080/").toString());
        assertEquals("http://intranet/board", PageUrl.check("http://intranet/board").toString());
        for (String url : List.of("file:///etc/passwd", "file://C:/Users/yi/secret.txt", "chrome://settings", "about:blank",
                "javascript:alert(1)", "data:text/html,<p>x</p>", "view-source:https://example.com", "ftp://example.com/a", "example.com",
                "")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> PageUrl.check(url), url);
            assertEquals("Only http and https URLs are allowed.", refused.getMessage(), url);
        }
        assertEquals("URLs with a user name or password are not allowed.",
                assertThrows(IllegalArgumentException.class, () -> PageUrl.check("https://yi:pw@example.com/")).getMessage());
    }
}
