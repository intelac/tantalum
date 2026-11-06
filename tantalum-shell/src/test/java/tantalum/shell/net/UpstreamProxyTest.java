package tantalum.shell.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.shell.net.UpstreamProxy.Direct;
import tantalum.shell.net.UpstreamProxy.Route;
import tantalum.shell.net.UpstreamProxy.Via;

/** W12：照环境变量选上游代理，照 DSH outbound-proxy note。 */
class UpstreamProxyTest {

    private static UpstreamProxy from(Map<String, String> env) {
        return UpstreamProxy.fromEnvironment(env::get);
    }

    @Test
    void bothCasesAreReadAndLowerCaseWins() {
        UpstreamProxy upper = from(Map.of("HTTPS_PROXY", "http://proxy.corp:3128", "HTTP_PROXY", "proxy.corp:8080"));
        assertEquals(new Via("proxy.corp", 3128, null), upper.route("example.com", 443, true));
        assertEquals(new Via("proxy.corp", 8080, null), upper.route("example.com", 80, false));
        UpstreamProxy both = from(Map.of("https_proxy", "http://lower:1", "HTTPS_PROXY", "http://upper:2"));
        assertEquals(new Via("lower", 1, null), both.route("example.com", 443, true));
        assertEquals(new Direct(), both.route("example.com", 80, false), "no HTTP_PROXY means plain http goes direct");
    }

    /** DSH note:35：空值当没设，落到另一种写法上。 */
    @Test
    void emptyValuesCountAsUnset() {
        assertEquals(new Direct(), from(Map.of("HTTPS_PROXY", "", "HTTP_PROXY", "  ")).route("example.com", 443, true));
        assertEquals(new Via("upper", 2, null), from(Map.of("https_proxy", "", "HTTPS_PROXY", "http://upper:2")).route("a.b", 443, true));
    }

    @Test
    void noProxyMatchesNamesSuffixesAndStar() {
        UpstreamProxy proxy = from(Map.of("HTTPS_PROXY", "http://p:1", "NO_PROXY", "intranet.local, .example.com,10.0.0.7:8443"));
        assertEquals(new Direct(), proxy.route("intranet.local", 443, true));
        assertEquals(new Direct(), proxy.route("docs.example.com", 443, true));
        assertEquals(new Direct(), proxy.route("example.com", 443, true));
        assertEquals(new Direct(), proxy.route("10.0.0.7", 443, true));
        assertTrue(proxy.route("notexample.com", 443, true) instanceof Via);
        assertTrue(proxy.route("other.local", 443, true) instanceof Via);
        assertEquals(new Direct(), from(Map.of("HTTPS_PROXY", "http://p:1", "no_proxy", "*")).route("anything.com", 443, true));
    }

    /** DSH note:35：回环地址永远不走代理。 */
    @Test
    void loopbackNeverGoesThroughTheProxy() {
        UpstreamProxy proxy = from(Map.of("HTTPS_PROXY", "http://p:1", "HTTP_PROXY", "http://p:1"));
        for (String host : new String[] {"localhost", "127.0.0.1", "127.9.9.9", "::1", "[::1]"}) {
            assertEquals(new Direct(), proxy.route(host, 80, false), host);
            assertEquals(new Direct(), proxy.route(host, 443, true), host);
        }
    }

    @Test
    void credentialsBecomeABasicHeaderAndStayOutOfToString() {
        Route route = from(Map.of("HTTPS_PROXY", "http://al%40ce:p+ss%3Aword@proxy:3128")).route("example.com", 443, true);
        Via via = (Via) route;
        String decoded = new String(Base64.getDecoder().decode(via.authorization().substring("Basic ".length())), StandardCharsets.UTF_8);
        assertEquals("al@ce:p+ss:word", decoded);
        assertFalse(via.toString().contains("p+ss"), via.toString());
        assertFalse(via.toString().contains(via.authorization()), via.toString());
    }

    /** DSH note:37：SOCKS 和读不懂的值改为直连，WARN 一行，日志里没有代理网址和用户名密码。 */
    @Test
    void socksAndGarbageGoDirectWithAWarningThatHidesTheValue() {
        try (LogCapture log = new LogCapture()) {
            UpstreamProxy proxy = from(Map.of(
                    "HTTPS_PROXY", "socks5://secretuser:secretpass@socks.corp:1080",
                    "HTTP_PROXY", "http://secretuser:secretpass@[bad"));
            assertEquals(new Direct(), proxy.route("example.com", 443, true));
            assertEquals(new Direct(), proxy.route("example.com", 80, false));
            String all = log.all();
            assertEquals(2, log.lines().stream().filter(line -> line.startsWith("WARN")).count(), all);
            assertTrue(all.contains("HTTPS_PROXY") && all.contains("HTTP_PROXY"), all);
            for (String secret : new String[] {"secretuser", "secretpass", "socks.corp", "1080", "[bad"}) {
                assertFalse(all.contains(secret), all);
            }
        }
    }
}
