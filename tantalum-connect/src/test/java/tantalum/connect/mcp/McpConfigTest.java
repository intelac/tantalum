package tantalum.connect.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code mcp.json} 的读法（connector-jar 需求 6.1）：格式同 Claude Desktop，错的那一项单独说，不影响别的。 */
class McpConfigTest {

    @Test
    void readsTheClaudeDesktopFormat() {
        McpConfig config = McpConfig.parse("""
                {"mcpServers": {
                  "sqlite": {"command": "uvx", "args": ["mcp-server-sqlite", "--db-path", "a.db"], "env": {"X": "1"}, "cwd": "data"},
                  "off": {"command": "npx", "disabled": true}
                }}""");
        assertEquals(List.of(), config.problems());
        McpConfig.Entry sqlite = config.servers().getFirst();
        assertEquals("sqlite", sqlite.name());
        assertEquals("uvx", sqlite.command());
        assertEquals(List.of("mcp-server-sqlite", "--db-path", "a.db"), sqlite.args());
        assertEquals(Map.of("X", "1"), sqlite.env());
        assertEquals(Path.of("data"), sqlite.cwd());
        assertTrue(config.servers().get(1).disabled());
    }

    @Test
    void eachBadEntryIsReportedAndTheRestKept() {
        McpConfig config = McpConfig.parse("""
                {"mcpServers": {
                  "has space": {"command": "x"},
                  "remote": {"url": "https://example.com/mcp"},
                  "badargs": {"command": "x", "args": "not a list"},
                  "good": {"command": "x"}
                }}""");
        assertEquals(List.of("good"), config.servers().stream().map(McpConfig.Entry::name).toList());
        assertEquals(3, config.problems().size());
        assertTrue(config.problems().get(1).contains("only local programs over stdio"), config.problems().get(1));
    }

    @Test
    void notJsonOrMissing() {
        assertTrue(McpConfig.parse("{oops").problems().getFirst().startsWith("not valid JSON"));
        assertEquals(McpConfig.empty(), McpConfig.parse("{}"));
        assertEquals(McpConfig.empty(), McpConfig.read(Path.of("/no/such/mcp.json")));
    }

    @Test
    void defaultPlaces() {
        Path home = Path.of("/home/yi");
        assertEquals(home.resolve(".config/tantalum/mcp.json"), McpConfig.defaultPath(Map.of(), "Linux", home));
        assertEquals(Path.of("/x/tantalum/mcp.json"), McpConfig.defaultPath(Map.of("XDG_CONFIG_HOME", "/x"), "Mac OS X", home));
        assertEquals(Path.of("C:\\Users\\yi\\AppData\\Roaming", "tantalum", "mcp.json"),
                McpConfig.defaultPath(Map.of("APPDATA", "C:\\Users\\yi\\AppData\\Roaming"), "Windows 11", home));
    }

    @Test
    void theFileWinsOverTheBuiltInEntry() {
        McpConfig config = McpConfig.parse("{\"mcpServers\": {\"playwright\": {\"command\": \"mine\"}}}");
        McpConfig merged = config.withDefault(new McpConfig.Entry("playwright", "npx", List.of(), Map.of(), null, false));
        assertEquals("mine", merged.servers().getFirst().command());
        assertEquals(2, McpConfig.empty().withDefault(new McpConfig.Entry("a", "x", List.of(), Map.of(), null, false))
                .withDefault(new McpConfig.Entry("b", "x", List.of(), Map.of(), null, false)).servers().size());
    }

    @Test
    void windowsWrapsNonExeCommandsInCmd() {
        assertEquals(List.of("cmd.exe", "/d", "/s", "/c", "npx", "-y", "pkg"), McpProcess.commandLine("npx", List.of("-y", "pkg"), "Windows 11"));
        assertEquals(List.of("C:\\node\\node.exe", "a"), McpProcess.commandLine("C:\\node\\node.exe", List.of("a"), "Windows 11"));
        assertEquals(List.of("npx", "-y"), McpProcess.commandLine("npx", List.of("-y"), "Mac OS X"));
    }
}
