package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** remove-generic-mcp 需求 1.2：旧的 {@code tantalum.mcp.*} 还写着时认出来（照常启动，只 WARN）。 */
class RetiredMcpTest {

    @Test
    void oldMcpSettingsAreFoundInEveryForm() {
        assertEquals(List.of("tantalum.mcp.startup-wait", "tantalum.mcp.servers.github.url", "TANTALUM_MCP_SERVERS_GITHUB_URL"),
                RetiredMcp.staleKeys(List.of("server.port", "tantalum.mcp.startup-wait", "tantalum.mcp.servers.github.url",
                        "tantalum.mcp.startup-wait", "TANTALUM_MCP_SERVERS_GITHUB_URL", "tantalum.mcpx", "TANTALUM_MODEL")));
    }

    @Test
    void nothingIsFoundWithoutThem() {
        assertEquals(List.of(), RetiredMcp.staleKeys(List.of("tantalum.provider", "tantalum.web.fetch.enabled")));
    }
}
