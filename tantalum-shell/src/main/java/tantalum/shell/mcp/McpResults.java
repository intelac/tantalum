package tantalum.shell.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
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
                text.append(describe(content));
            }
        }
        boolean error = Boolean.TRUE.equals(result.isError());
        if (text.isEmpty() && result.structuredContent() != null) {
            text.append(structured(result.structuredContent()));
        }
        if (text.isEmpty() && error) {
            text.append("The tool reported an error without a message.");
        }
        return new ToolOutput(truncate(text.toString(), maxBytes), error);
    }

    private static String structured(Object value) {
        try {
            return McpJsonDefaults.getMapper().writeValueAsString(value);
        } catch (IOException failure) {
            return "[structured content could not be read]";
        }
    }

    private static String describe(McpSchema.Content content) {
        return switch (content) {
            case McpSchema.TextContent plain -> plain.text();
            case McpSchema.ResourceLink link -> "[resource " + link.name() + ": " + link.uri() + "]";
            default -> "[" + content.type() + " content omitted]";
        };
    }

    /** 保留首尾，结论往往在末尾（pi 相同）。总长含说明不超上限，不切坏字符。 */
    static String truncate(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        String note = "\n(result truncated: " + bytes.length + " bytes, limit " + maxBytes + ")\n";
        int budget = Math.max(0, maxBytes - note.length());
        int headEnd = budget / 2;
        while (headEnd > 0 && (bytes[headEnd] & 0xC0) == 0x80) {
            headEnd--;
        }
        int tailStart = bytes.length - (budget - budget / 2);
        while (tailStart < bytes.length && (bytes[tailStart] & 0xC0) == 0x80) {
            tailStart++;
        }
        return new String(bytes, 0, headEnd, StandardCharsets.UTF_8) + note
                + new String(bytes, tailStart, bytes.length - tailStart, StandardCharsets.UTF_8);
    }
}
