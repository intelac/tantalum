package tantalum.shell.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import tantalum.shell.attachment.ToolImages;
import tantalum.value.attachment.ImageRef;

/**
 * 纯函数：把 MCP 结果变成文字和图片。收文本和 PNG、JPEG、WebP、GIF 图片（web-tools W8，照 DSH {@code mcp/mcp-client/src/tools.ts:333-397}），
 * 其他内容换成一行说明；文字总长按 UTF-8 字节截断，不切坏字符，图片不算在里面。图片的 base64 不进文字。
 */
final class McpResults {

    /** 一次调用的结果。{@code images} 由 {@link McpTool} 交给 {@link ToolImages} 存进本人附件。 */
    record Result(String text, boolean error, List<ToolImages.Image> images) {

        Result {
            images = List.copyOf(images);
        }

        static Result error(String text) {
            return new Result(text, true, List.of());
        }
    }

    private McpResults() {}

    static Result toOutput(McpSchema.CallToolResult result, int maxBytes, int maxImages) {
        StringBuilder text = new StringBuilder();
        List<ToolImages.Image> images = new ArrayList<>();
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                String line;
                if (content instanceof McpSchema.ImageContent image) {
                    line = image(image, images, maxImages);
                    if (line == null) {
                        continue;
                    }
                } else {
                    line = describe(content);
                }
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(line);
            }
        }
        boolean error = Boolean.TRUE.equals(result.isError());
        if (text.isEmpty() && result.structuredContent() != null) {
            text.append(structured(result.structuredContent()));
        }
        if (text.isEmpty() && error) {
            text.append("The tool reported an error without a message.");
        }
        return new Result(truncate(text.toString(), maxBytes), error, images);
    }

    /**
     * 收下的图片放进 {@code images}，返回 null；收不下的返回一行说明。base64 要是规范写法（解码再编码和原文一样，同 DSH
     * {@code tools.ts:357-370}）：多余的空白、填充这类写法各家解码器认法不同，存的和服务器想给的就可能不是同一张图。
     */
    private static String image(McpSchema.ImageContent image, List<ToolImages.Image> images, int maxImages) {
        String type = image.mimeType() == null ? "unknown" : image.mimeType().strip().toLowerCase(Locale.ROOT);
        if (!ImageRef.MEDIA_TYPES.contains(type)) {
            return "[" + type + " image omitted: only PNG, JPEG, WebP and GIF images are kept]";
        }
        byte[] bytes;
        try {
            bytes = image.data() == null ? null : Base64.getDecoder().decode(image.data());
        } catch (IllegalArgumentException invalid) {
            bytes = null;
        }
        if (bytes == null || bytes.length == 0 || !Base64.getEncoder().encodeToString(bytes).equals(image.data())) {
            return "[" + type + " image omitted: the image data is not valid base64]";
        }
        if (images.size() >= maxImages) {
            return "[" + type + " image omitted: at most " + maxImages + " images are kept from one tool result]";
        }
        images.add(new ToolImages.Image(type, bytes));
        return null;
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
