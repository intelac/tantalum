package tantalum.shell.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpToolFilterTest {

    private static final McpSettings.Server SERVER = new McpSettings.Server("s", URI.create("https://x/mcp"), McpSettings.Auth.NONE, null,
            Set.of("create", "both"), Set.of("both", "dangerous"), 4, Duration.ofSeconds(1), 100, 10);

    @Test
    void denyWinsThenAllowThenReadOnly() {
        assertFalse(McpToolFilter.allowed(SERVER, "both", true));
        assertFalse(McpToolFilter.allowed(SERVER, "dangerous", true));
        assertTrue(McpToolFilter.allowed(SERVER, "create", null));
        assertTrue(McpToolFilter.allowed(SERVER, "search", true));
    }

    @Test
    void undeclaredOrWritingToolsStayHidden() {
        assertFalse(McpToolFilter.allowed(SERVER, "search", null));
        assertFalse(McpToolFilter.allowed(SERVER, "search", false));
    }

    @Test
    void publicNamesAreNamespacedAndChecked() {
        assertEquals(Optional.of("mcp__jira__search"), McpToolFilter.publicName("jira", "search"));
        assertTrue(McpToolFilter.publicName("jira", "has space").isEmpty());
        assertTrue(McpToolFilter.publicName("jira", "a.b").isEmpty());
        assertTrue(McpToolFilter.publicName("jira", "x".repeat(60)).isEmpty());
    }
}
