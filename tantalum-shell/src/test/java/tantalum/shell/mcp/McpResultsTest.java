package tantalum.shell.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.plugin.ToolOutput;

class McpResultsTest {

    private static McpSchema.CallToolResult result(boolean error, McpSchema.Content... content) {
        return McpSchema.CallToolResult.builder().content(List.of(content)).isError(error).build();
    }

    @Test
    void textIsJoinedAndErrorFlagKept() {
        ToolOutput output = McpResults.toOutput(result(true, new McpSchema.TextContent("a"), new McpSchema.TextContent("b")), 100);
        assertEquals("a\nb", output.text());
        assertTrue(output.error());
        assertFalse(McpResults.toOutput(result(false, new McpSchema.TextContent("a")), 100).error());
    }

    @Test
    void otherContentBecomesANote() {
        McpSchema.Content image = new McpSchema.ImageContent(null, "AAAA", "image/png");
        ToolOutput output = McpResults.toOutput(result(false, new McpSchema.TextContent("a"), image), 100);
        assertEquals("a\n[image content omitted]", output.text());
        assertFalse(output.text().contains("AAAA"));
    }

    @Test
    void truncationKeepsWholeCharactersAndSaysSo() {
        assertEquals("短", McpResults.truncate("短", 3));
        String cut = McpResults.truncate("汉字汉字", 7);
        assertTrue(cut.startsWith("汉字\n"), cut);
        assertTrue(cut.contains("12 bytes, limit 7"), cut);
    }
}
