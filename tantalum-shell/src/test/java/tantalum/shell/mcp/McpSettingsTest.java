package tantalum.shell.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.shell.boot.TantalumProperties.Mcp;
import tantalum.shell.boot.TantalumProperties.McpServer;

class McpSettingsTest {

    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(60);
    private static final String SECRET = "very-secret-token";

    private static McpServer server(String url, String auth, String env) {
        return new McpServer(url, auth, env, null, null, null, null, null, null);
    }

    private static McpSettings load(String name, McpServer server) {
        return McpSettings.from(new Mcp(Map.of(name, server)), TOOL_TIMEOUT, key -> key.equals("TOKEN") ? SECRET : null);
    }

    private static String failure(String name, McpServer server) {
        return assertThrows(IllegalArgumentException.class, () -> load(name, server)).getMessage();
    }

    @Test
    void absentConfigurationMeansNoServers() {
        assertTrue(McpSettings.from(null, TOOL_TIMEOUT, key -> null).isEmpty());
        assertTrue(McpSettings.from(new Mcp(null), TOOL_TIMEOUT, key -> null).isEmpty());
    }

    @Test
    void defaultsAreApplied() {
        McpSettings.Server loaded = load("jira", server("https://mcp.example.com/mcp", null, null)).servers().getFirst();
        assertEquals(McpSettings.Auth.NONE, loaded.auth());
        assertEquals(4, loaded.concurrency());
        assertEquals(TOOL_TIMEOUT, loaded.timeout());
        assertEquals(64 * 1024, loaded.maxResultBytes());
        assertEquals(64, loaded.maxTools());
    }

    @Test
    void serviceTokenComesFromTheEnvironmentAndNeverPrints() {
        McpSettings.Server loaded = load("jira", server("https://mcp.example.com/mcp", "service", "TOKEN")).servers().getFirst();
        assertEquals(SECRET, loaded.token());
        assertFalse(loaded.toString().contains(SECRET));
    }

    @Test
    void invalidConfigurationNamesTheKeyWithoutCredentials() {
        assertTrue(failure("bad name", server("https://x/mcp", null, null)).contains("server name"));
        assertTrue(failure("a", server("ftp://x/mcp", null, null)).contains(".url"));
        assertTrue(failure("a", server(null, null, null)).contains(".url"));
        assertTrue(failure("a", server("not a url", null, null)).contains(".url"));
        assertTrue(failure("a", server("https://x/mcp", "user", null)).contains(".auth"));
        assertTrue(failure("a", server("https://x/mcp", "service", null)).contains(".token-env"));
        assertTrue(failure("a", server("https://x/mcp", "service", "MISSING")).contains("MISSING"));
        assertTrue(failure("a", server("https://u:p@x/mcp", null, null)).contains("credentials"));
        String plain = failure("a", server("http://mcp.example.com/mcp", "service", "TOKEN"));
        assertTrue(plain.contains("plain http"));
        assertFalse(plain.contains(SECRET));
    }

    @Test
    void plainHttpIsAllowedWithoutCredentialsOrOnLoopback() {
        assertFalse(load("a", server("http://mcp.example.com/mcp", null, null)).isEmpty());
        assertFalse(load("a", server("http://127.0.0.1:9000/mcp", "service", "TOKEN")).isEmpty());
    }

    @Test
    void limitsAreChecked() {
        McpServer tooLong = new McpServer("https://x/mcp", null, null, null, null, null, Duration.ofSeconds(61), null, null);
        assertTrue(failure("a", tooLong).contains(".timeout"));
        McpServer zero = new McpServer("https://x/mcp", null, null, List.of("a"), List.of("b"), 0, null, null, null);
        assertTrue(failure("a", zero).contains(".concurrency"));
    }
}
