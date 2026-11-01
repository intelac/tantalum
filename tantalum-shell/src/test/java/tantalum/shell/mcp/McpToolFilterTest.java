package tantalum.shell.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
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
    void publicNamesAreNamespacedAndAlwaysLegal() {
        assertEquals("mcp__jira__search", McpToolFilter.publicName("jira", "search"));
        for (String tool : java.util.List.of("has space", "a.b", "x".repeat(60), "汉字")) {
            String name = McpToolFilter.publicName("jira", tool);
            assertTrue(name.matches("[A-Za-z0-9_-]{1,64}"), name);
            assertEquals(name, McpToolFilter.publicName("jira", tool), "stable across restarts");
        }
        assertNotEquals(McpToolFilter.publicName("jira", "a.b"), McpToolFilter.publicName("jira", "a_b"), "no collision");
        assertNotEquals(McpToolFilter.publicName("jira", "x".repeat(60) + "1"), McpToolFilter.publicName("jira", "x".repeat(60) + "2"));
    }
}
