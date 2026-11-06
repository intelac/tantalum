package tantalum.shell.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
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
}
