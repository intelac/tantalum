package tantalum.kernel.compaction;

import java.nio.charset.StandardCharsets;
import java.util.List;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.AttachmentText;
import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;
import tantalum.value.json.Json;
import tantalum.value.model.AttachmentBlock;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.model.ToolSpec;

/** 规则 1 的估算：UTF-8 字节数除以 3 向上取整，所有模型相同，宁可高估。 */
public final class TokenEstimate {

    static final long PDF_PAGE_TOKENS = 3000;

    private TokenEstimate() {}

    public static long ofText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (text.getBytes(StandardCharsets.UTF_8).length + 2) / 3;
    }

    public static long ofMessage(Message message) {
        long total = 0;
        for (ContentBlock block : message.content()) {
            total += ofBlock(block);
        }
        return total;
    }

    public static long ofMessages(List<Message> messages) {
        long total = 0;
        for (Message message : messages) {
            total += ofMessage(message);
        }
        return total;
    }

    public static long ofTools(List<ToolSpec> tools) {
        long total = 0;
        for (ToolSpec tool : tools) {
            total += ofText(tool.name()) + ofText(tool.description()) + ofJson(tool.schema());
        }
        return total;
    }

    private static long ofBlock(ContentBlock block) {
        return switch (block) {
            case TextBlock text -> ofText(text.text());
            case ReasoningBlock reasoning -> ofJson(reasoning.raw());
            case ToolCallBlock call -> ofText(call.name()) + ofText(call.rawArguments());
            case ToolResultBlock result -> ofText(result.text()) + result.images().stream().mapToLong(TokenEstimate::ofImage).sum();
            case AttachmentBlock attachment -> ofAttachment(attachment.ref());
            // 按 OpenAI 那边的文字说明算，比 Claude 的 tool_reference 块长，宁可高估
            case ToolChangeBlock change -> ofText("Tools changed: added " + change.added() + "; removed " + change.removed() + ".");
        };
    }

    /** 图片按像素算（A11）：{@code ceil(宽 × 高 / 750)}，加上前面那行说明。 */
    public static long ofImage(ImageRef image) {
        return ((long) image.width() * image.height() + 749) / 750 + ofText(AttachmentText.image(image));
    }

    /** 文件只发说明文字；PDF 另按每页 3000 token 算文档块（Anthropic 文档写的每页 1500–3000，取上限）。 */
    private static long ofAttachment(AttachmentRef ref) {
        return switch (ref) {
            case ImageRef image -> ofImage(image);
            case FileRef file -> ofText(AttachmentText.file(file)) + (file.pdf() ? file.pages() * PDF_PAGE_TOKENS : 0);
        };
    }

    /** 按紧凑 JSON 文本的字节数算，不要求和真实序列化逐字节相同。 */
    private static long ofJson(Json json) {
        return json == null ? 0 : (jsonBytes(json) + 2) / 3;
    }

    private static long jsonBytes(Json json) {
        return switch (json) {
            case Json.Null ignored -> 4;
            case Json.Bool bool -> bool.value() ? 4 : 5;
            case Json.Num num -> num.value().toPlainString().length();
            case Json.Str str -> str.value().getBytes(StandardCharsets.UTF_8).length + 2;
            case Json.Arr arr -> {
                long bytes = 2;
                for (Json item : arr.items()) {
                    bytes += jsonBytes(item) + 1;
                }
                yield bytes;
            }
            case Json.Obj obj -> {
                long bytes = 2;
                for (var field : obj.fields().entrySet()) {
                    bytes += field.getKey().getBytes(StandardCharsets.UTF_8).length + 4 + jsonBytes(field.getValue());
                }
                yield bytes;
            }
        };
    }
}
