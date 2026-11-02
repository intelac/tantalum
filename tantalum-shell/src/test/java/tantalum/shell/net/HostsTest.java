package tantalum.shell.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HostsTest {

    /** 回环判断只认字面量：MCP 的明文 http 和 Cookie 的 Secure 检查都靠它。 */
    @Test
    void loopbackIsLiteralOnly() {
        for (String host : List.of("localhost", "127.0.0.1", "127.1.2.3", "[::1]", "::1")) {
            assertTrue(Hosts.isLoopback(host), host);
        }
        for (String host : List.of("example.com", "127.0.0.1.example.com", "10.0.0.1", "0.0.0.0", "", "127.evil.example")) {
            assertFalse(Hosts.isLoopback(host), host);
        }
        assertFalse(Hosts.isLoopback(null));
    }
}
