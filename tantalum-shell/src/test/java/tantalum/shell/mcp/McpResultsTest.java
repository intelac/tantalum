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
    void truncationKeepsHeadAndTailWholeCharactersAndSaysSo() {
        assertEquals("短", McpResults.truncate("短", 3));
        String cut = McpResults.truncate("汉".repeat(100) + "END", 80);
        assertTrue(cut.startsWith("汉"), cut);
        assertTrue(cut.endsWith("END"), cut);
        assertTrue(cut.contains("303 bytes, limit 80"), cut);
        assertTrue(cut.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 80);
        assertTrue(!cut.contains("\uFFFD"), cut);
    }

    @Test
    void resourceLinksKeepTheirNameAndUri() {
        var link = McpSchema.ResourceLink.builder().name("report").uri("file:///r.txt").build();
        var result = McpSchema.CallToolResult.builder().content(List.of(link)).build();
        assertEquals("[resource report: file:///r.txt]", McpResults.toOutput(result, 1024).text());
    }

    @Test
    void emptyContentFallsBackToStructuredContentAndEmptyErrorsSaySo() {
        var structured = McpSchema.CallToolResult.builder().structuredContent(java.util.Map.of("owner", "M")).build();
        assertEquals("{\"owner\":\"M\"}", McpResults.toOutput(structured, 1024).text());
        var empty = McpSchema.CallToolResult.builder().isError(true).build();
        assertTrue(!McpResults.toOutput(empty, 1024).text().isEmpty());
    }
}
