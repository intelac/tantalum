package tantalum.shell.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 写操作的 {@code Origin} 必须就是浏览器访问的这个主机。 */
class OriginFilterTest {

    /** {@code host} 是浏览器发来的 Host 头；服务器自己看到的主机取它去掉端口的部分。 */
    private static boolean same(String host, int serverPort, String origin) {
        String name = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        return SecurityConfig.OriginFilter.sameHost(origin, name, serverPort, host);
    }

    @Test
    void theSameHostAndPortIsAccepted() {
        assertTrue(same("127.0.0.1:8091", 8091, "http://127.0.0.1:8091"));
        assertTrue(same("tantalum.example", 443, "https://tantalum.example"));
    }

    /** 只做 HTTPS、不加 X-Forwarded-* 的代理后面：服务器看到的是 http 和 80 端口，浏览器的 Origin 是 https。 */
    @Test
    void anHttpsProxyThatAddsNoForwardedHeadersIsAccepted() {
        assertTrue(same("tantalum.example", 80, "https://tantalum.example"));
        assertTrue(same("Tantalum.Example", 80, "https://tantalum.example"));
    }

    @Test
    void anotherHostIsRefusedWhateverTheScheme() {
        assertFalse(same("tantalum.example", 80, "https://evil.example"));
        assertFalse(same("tantalum.example", 80, "https://tantalum.example.evil.example"));
        assertFalse(same("127.0.0.1:8091", 8091, "https://evil.example"));
        assertFalse(same("tantalum.example", 80, "not a url"));
        assertFalse(same("tantalum.example", 80, "null"));
    }

    @Test
    void anotherPortOnTheSameHostIsStillRefused() {
        assertFalse(same("tantalum.example", 80, "https://tantalum.example:8443"));
        assertFalse(same("tantalum.example:8091", 8091, "https://tantalum.example"));
        assertFalse(same("127.0.0.1:8091", 8091, "http://127.0.0.1:9000"));
    }
}
