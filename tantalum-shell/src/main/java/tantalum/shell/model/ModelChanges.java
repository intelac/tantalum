package tantalum.shell.model;

import java.util.ArrayList;
import java.util.List;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChangeBlock;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolResultBlock;

/**
 * 换模型的说明怎么发（model-picker M12）：历史里它是紧跟这次用户消息的系统消息，发出去时并进前面那条用户消息、写成开头的一段文字。
 * 不用会话中途的 system 消息：Claude Sonnet 5 不支持，有的聊天模板只认第一条 system，而说明在每个模型上要一样。
 * 同一条用户消息前面有几条说明时按发生的顺序排；前面那条用户消息带工具结果时（工具结果要在最前面）写在末尾；前面不是用户消息时
 * 单独成一条用户消息。纯函数，每次发出的字节相同。
 */
final class ModelChanges {

    private ModelChanges() {}

    static List<Message> fold(List<Message> messages) {
        if (messages.stream().noneMatch(ModelChanges::isModelChange)) {
            return messages;
        }
        List<Message> out = new ArrayList<>();
        // 最后一条输出消息开头已经并进了几段说明，下一段排在它们后面
        int folded = 0;
        for (Message message : messages) {
            if (!isModelChange(message)) {
                out.add(message);
                folded = 0;
                continue;
            }
            Message last = out.isEmpty() ? null : out.getLast();
            if (last == null || last.role() != Role.USER) {
                out.add(new Message(Role.USER, notices(message, false), false));
                folded = message.content().size();
                continue;
            }
            List<ContentBlock> merged = new ArrayList<>(last.content());
            if (last.content().stream().anyMatch(ToolResultBlock.class::isInstance)) {
                merged.addAll(notices(message, false));
            } else {
                // 写在用户的话前面时空一行隔开（OpenAI 兼容的把文字段直接拼在一起）
                List<ContentBlock> notices = notices(message, true);
                merged.addAll(folded, notices);
                folded += notices.size();
            }
            out.set(out.size() - 1, new Message(Role.USER, merged, last.interrupted()));
        }
        return out;
    }

    private static List<ContentBlock> notices(Message message, boolean before) {
        return message.content().stream()
                .map(block -> (ContentBlock) new TextBlock(((ModelChangeBlock) block).text() + (before ? "\n\n" : ""))).toList();
    }

    static boolean isModelChange(Message message) {
        return message.role() == Role.SYSTEM && !message.content().isEmpty()
                && message.content().stream().allMatch(ModelChangeBlock.class::isInstance);
    }
}
