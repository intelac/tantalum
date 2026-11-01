package tantalum.shell.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import tantalum.shell.plugin.ToolOutput;

/** 纯函数：把 MCP 结果变成工具输出。只接受文本，其他内容换成一行说明；总长按 UTF-8 字节截断，不切坏字符。 */
final class McpResults {

    private McpResults() {}

    static ToolOutput toOutput(McpSchema.CallToolResult result, int maxBytes) {
        StringBuilder text = new StringBuilder();
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(content instanceof McpSchema.TextContent plain ? plain.text()
                        : "[" + content.type() + " content omitted]");
            }
        }
        return new ToolOutput(truncate(text.toString(), maxBytes), Boolean.TRUE.equals(result.isError()));
    }

    static String truncate(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE);
        CharBuffer kept = CharBuffer.allocate(maxBytes);
        decoder.decode(ByteBuffer.wrap(bytes, 0, maxBytes), kept, false);
        kept.flip();
        return kept + "\n(result truncated: " + bytes.length + " bytes, limit " + maxBytes + ")";
    }
}
