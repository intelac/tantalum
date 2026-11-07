package tantalum.kernel.compaction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolResultBlock;

/**
 * 规则 5 对保留段、规则 6 对头部的处理：去掉全部推理块（不看供应商）和工具变化（压缩后重新组装，新的工具名单从现在的开关算起），过长的工具结果截断并注明原长度，处理后内容为空的消息丢弃（返回 null）。文本和工具调用逐字不动。
 * 截断保留开头和结尾各一半：文件的暗号、日志的结论、测试的结果往往在末尾，只留开头会把它们丢掉。
 */
public final class Trimming {

    private static final Pattern MARKER = Pattern.compile("\\n\\[\\.\\.\\. (\\d+) of (\\d+) bytes truncated \\.\\.\\.\\]\\n");

    private Trimming() {}

    public static Message trim(Message message, int maxResultBytes) {
        List<ContentBlock> kept = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            switch (block) {
                case ReasoningBlock ignored -> { }
                case ToolChangeBlock ignored -> { }
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

    /** 标记算进上限。已经被截断过的结果先去掉旧标记，沿用最初的原长度，所以不会嵌套。上限小于标记时只留标记。 */
    static ToolResultBlock truncate(ToolResultBlock result, int maxBytes) {
        byte[] whole = result.text().getBytes(StandardCharsets.UTF_8);
        if (whole.length <= maxBytes) {
            return result;
        }
        String text = result.text();
        Matcher old = MARKER.matcher(text);
        long original = whole.length;
        if (old.find()) {
            original = Long.parseLong(old.group(2));
            text = text.substring(0, old.start()) + text.substring(old.end());
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int budget = Math.max(0, maxBytes - marker(original, original).length());
        int headEnd = charBoundaryBackward(bytes, budget / 2);
        int tailStart = charBoundaryForward(bytes, bytes.length - (budget - budget / 2));
        String head = new String(bytes, 0, headEnd, StandardCharsets.UTF_8);
        String tail = new String(bytes, tailStart, bytes.length - tailStart, StandardCharsets.UTF_8);
        long omitted = original - headEnd - (bytes.length - tailStart);
        return new ToolResultBlock(result.toolCallId(), head + marker(omitted, original) + tail, result.error(), result.code(),
                result.images());
    }

    private static String marker(long omitted, long original) {
        return "\n[... " + omitted + " of " + original + " bytes truncated ...]\n";
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
