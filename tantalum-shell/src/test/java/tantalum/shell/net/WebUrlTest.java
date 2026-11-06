package tantalum.shell.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 需求 1.2、W2：不合格的网址不发请求，错误消息照设计原文。 */
class WebUrlTest {

    private static final String SCHEME = "Only http and https URLs are allowed.";
    private static final String USER = "URLs with a user name or password are not allowed.";
    private static final String LONG = "The URL is longer than 2048 characters.";

    static Stream<Arguments> urls() {
        String base = "https://example.com/";
        return Stream.of(
                Arguments.of("https://example.com/a?q=1", null),
                Arguments.of("HTTP://Example.com", null),
                Arguments.of("ftp://example.com/file", SCHEME),
                Arguments.of("file:///etc/passwd", SCHEME),
                Arguments.of("javascript:alert(1)", SCHEME),
                Arguments.of("data:text/html,<p>x</p>", SCHEME),
                Arguments.of("chrome://settings", SCHEME),
                Arguments.of("example.com", SCHEME),
                Arguments.of("https://user:pass@example.com/", USER),
                Arguments.of("https://user@example.com/", USER),
                Arguments.of(base + "a".repeat(2048 - base.length()), null),
                Arguments.of(base + "a".repeat(2049 - base.length()), LONG));
    }

    @ParameterizedTest
    @MethodSource("urls")
    void checksSchemeCredentialsAndLength(String url, String error) {
        if (error == null) {
            assertEquals(url, WebUrl.check(url).toString());
        } else {
            assertEquals(error, assertThrows(IllegalArgumentException.class, () -> WebUrl.check(url)).getMessage(), url);
        }
    }

    /** 国际化域名转成 xn-- 写法，路径和查询里的非 ASCII 字符按 UTF-8 百分号编码：交给 HttpClient 和出口的是纯 ASCII。 */
    static Stream<Arguments> international() {
        return Stream.of(
                Arguments.of("https://例え.jp/", "https://xn--r8jz45g.jp/"),
                Arguments.of("http://bücher.example:8080/x", "http://xn--bcher-kva.example:8080/x"),
                Arguments.of("https://中文.中国", "https://xn--fiq228c.xn--fiqs8s"),
                Arguments.of("https://例え。jp/", "https://xn--r8jz45g.jp/"),
                Arguments.of("https://example.com/パス?q=日本", "https://example.com/%E3%83%91%E3%82%B9?q=%E6%97%A5%E6%9C%AC"));
    }

    @ParameterizedTest
    @MethodSource("international")
    void internationalNamesAndPathsBecomeAscii(String url, String ascii) {
        assertEquals(ascii, WebUrl.check(url).toString());
        assertEquals(WebUrl.check(url).getHost(), java.net.URI.create(ascii).getHost(), "the host is usable by HttpClient");
    }

    @Test
    void underscoreHostsAreRefusedWithAReason() {
        assertEquals("The URL is not valid: host names with an underscore (_) cannot be opened.",
                assertThrows(IllegalArgumentException.class, () -> WebUrl.check("https://my_host.example.com/")).getMessage());
        assertEquals(USER, assertThrows(IllegalArgumentException.class, () -> WebUrl.check("https://user:pass@例え.jp/")).getMessage());
    }
}
