package tantalum.shell.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpResultsTest {

    private static McpSchema.CallToolResult result(boolean error, McpSchema.Content... content) {
        return McpSchema.CallToolResult.builder().content(List.of(content)).isError(error).build();
    }

    @Test
    void textIsJoinedAndErrorFlagKept() {
        McpResults.Result output = McpResults.toOutput(result(true, new McpSchema.TextContent("a"), new McpSchema.TextContent("b")), 100, 20);
        assertEquals("a\nb", output.text());
        assertTrue(output.error());
        assertFalse(McpResults.toOutput(result(false, new McpSchema.TextContent("a")), 100, 20).error());
    }

    @Test
    void otherContentBecomesANote() {
        McpSchema.Content audio = new McpSchema.AudioContent(null, "AAAA", "audio/wav");
        McpResults.Result output = McpResults.toOutput(result(false, new McpSchema.TextContent("a"), audio), 100, 20);
        assertEquals("a\n[audio content omitted]", output.text());
        assertFalse(output.text().contains("AAAA"));
    }

    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    private static final String PNG_DATA = Base64.getEncoder().encodeToString(PNG_BYTES);

    /** 需求 3.2：PNG 收下交给附件，base64 不进文字。 */
    @Test
    void pngImagesAreKeptOutOfTheText() {
        McpSchema.Content image = new McpSchema.ImageContent(null, PNG_DATA, "image/png");
        McpResults.Result output = McpResults.toOutput(result(false, new McpSchema.TextContent("chart"), image), 100, 20);
        assertEquals("chart", output.text());
        assertEquals(1, output.images().size());
        assertEquals("image/png", output.images().getFirst().mediaType());
        assertArrayEquals(PNG_BYTES, output.images().getFirst().bytes());
    }

    /** 需求 3.2：别的类型、不规范的 base64、超过张数，都换成一行说明，图片数据不进文字。 */
    @Test
    void imagesThatCannotBeKeptBecomeOneLine() {
        McpSchema.Content svg = new McpSchema.ImageContent(null, "PHN2Zz48L3N2Zz4=", "image/svg+xml");
        assertEquals("[image/svg+xml image omitted: only PNG, JPEG, WebP and GIF images are kept]",
                McpResults.toOutput(result(false, svg), 1024, 20).text());
        for (String bad : List.of(PNG_DATA + "\n", PNG_DATA.replace("=", ""), "%%%", "")) {
            McpResults.Result output = McpResults.toOutput(result(false, new McpSchema.ImageContent(null, bad, "image/png")), 1024, 20);
            assertEquals("[image/png image omitted: the image data is not valid base64]", output.text(), bad);
            assertTrue(output.images().isEmpty());
        }
        McpSchema.Content png = new McpSchema.ImageContent(null, PNG_DATA, "image/png");
        McpResults.Result capped = McpResults.toOutput(result(false, png, png, png), 1024, 2);
        assertEquals(2, capped.images().size());
        assertEquals("[image/png image omitted: at most 2 images are kept from one tool result]", capped.text());
        assertFalse(capped.text().contains(PNG_DATA));
    }

    /** 文字截断不算图片：长文字照样截，图片照样收。 */
    @Test
    void truncationDoesNotCountImages() {
        McpSchema.Content png = new McpSchema.ImageContent(null, PNG_DATA, "image/png");
        McpResults.Result output = McpResults.toOutput(result(false, new McpSchema.TextContent("x".repeat(500)), png), 100, 20);
        assertTrue(output.text().contains("limit 100"), output.text());
        assertEquals(1, output.images().size());
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
        assertEquals("[resource report: file:///r.txt]", McpResults.toOutput(result, 1024, 20).text());
    }

    @Test
    void emptyContentFallsBackToStructuredContentAndEmptyErrorsSaySo() {
        var structured = McpSchema.CallToolResult.builder().structuredContent(java.util.Map.of("owner", "M")).build();
        assertEquals("{\"owner\":\"M\"}", McpResults.toOutput(structured, 1024, 20).text());
        var empty = McpSchema.CallToolResult.builder().isError(true).build();
        assertTrue(!McpResults.toOutput(empty, 1024, 20).text().isEmpty());
    }
}
