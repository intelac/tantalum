package tantalum.kernel.compaction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.ToolResultBlock;

/**
 * 规则 5 对保留段、规则 6 对头部的处理：去掉全部推理块（不看供应商），过长的工具结果截断并注明原长度，
 * 处理后内容为空的消息丢弃（返回 null）。文本和工具调用逐字不动。
 * 截断保留开头和结尾各一半：文件的暗号、日志的结论、测试的结果往往在末尾，只留开头会把它们丢掉（真实 Claude 场景暴露的问题）。
 */
public final class Trimming {

    private Trimming() {}

    public static Message trim(Message message, int maxResultBytes) {
        List<ContentBlock> kept = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            switch (block) {
                case ReasoningBlock ignored -> { }
                case ToolResultBlock result -> kept.add(truncate(result, maxResultBytes));
                default -> kept.add(block);
            }
        }
        return kept.isEmpty() ? null : new Message(message.role(), kept, message.interrupted());
    }

    public static List<Message> trimAll(List<Message> messages, int maxResultBytes) {
        List<Message> out = new ArrayList<>();
        for (Message message : messages) {
            Message trimmed = trim(message, maxResultBytes);
            if (trimmed != null) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }

    static ToolResultBlock truncate(ToolResultBlock result, int maxBytes) {
        byte[] bytes = result.text().getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return result;
        }
        int headEnd = charBoundaryBackward(bytes, maxBytes / 2);
        int tailStart = charBoundaryForward(bytes, bytes.length - (maxBytes - maxBytes / 2));
        String head = new String(bytes, 0, headEnd, StandardCharsets.UTF_8);
        String tail = new String(bytes, tailStart, bytes.length - tailStart, StandardCharsets.UTF_8);
        return new ToolResultBlock(result.toolCallId(),
                head + "\n[... " + (tailStart - headEnd) + " of " + bytes.length + " bytes truncated ...]\n" + tail,
                result.error(), result.code());
    }

    /** 退到 UTF-8 字符边界：续字节是 10xxxxxx。 */
    private static int charBoundaryBackward(byte[] bytes, int at) {
        int cut = at;
        while (cut > 0 && cut < bytes.length && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return cut;
    }

    private static int charBoundaryForward(byte[] bytes, int at) {
        int cut = at;
        while (cut < bytes.length && (bytes[cut] & 0xC0) == 0x80) {
            cut++;
        }
        return cut;
    }
}
