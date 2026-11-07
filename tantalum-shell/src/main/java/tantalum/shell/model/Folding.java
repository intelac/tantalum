package tantalum.shell.model;

import java.util.ArrayList;
import java.util.List;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.HookTextBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChangeBlock;
import tantalum.value.model.Role;
import tantalum.value.model.SystemUpdates;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolResultBlock;

/**
 * 历史里的系统消息怎么并进用户消息发出去（review-fixes 需求 12.2，合并自原来的 {@code ModelChanges.fold} 和 {@code HookTexts.fold}）。
 * <ul>
 *   <li>换模型的说明（model-picker M12）：并进前面那条用户消息、写成开头的一段文字，所有模型都这样。同一条用户消息前面有几条说明时
 *       按发生的顺序排；前面那条用户消息带工具结果时（工具结果要在最前面）写在末尾；前面不是用户消息时单独成一条用户消息。</li>
 *   <li>钩子的说明（hook-patch 需求 3.2），只在 {@code hookTexts} 为真时：并进前面那条用户消息的末尾，每段前面加“不是用户写的”前缀；
 *       同一条消息里的工具变化丢掉。前面不是用户消息时单独成一条。为假时这些系统消息原样留着，由客户端按 system 发。</li>
 * </ul>
 * 纯函数，每次发出的字节相同。
 */
final class Folding {

    private Folding() {}

    static List<Message> fold(List<Message> messages, boolean hookTexts) {
        if (messages.stream().noneMatch(message -> SystemUpdates.isModelChange(message) || hookTexts && SystemUpdates.isSystemUpdate(message))) {
            return messages;
        }
        List<Message> out = new ArrayList<>();
        // 最后一条输出消息开头已经并进了几段换模型的说明，下一段排在它们后面
        int folded = 0;
        // 刚并进去的是钩子的说明：对换模型的说明来说，前面那条是系统消息，不是用户消息
        boolean afterHookTexts = false;
        for (Message message : messages) {
            if (SystemUpdates.isModelChange(message)) {
                Message last = out.isEmpty() ? null : out.getLast();
                if (last == null || last.role() != Role.USER || afterHookTexts) {
                    out.add(new Message(Role.USER, modelChanges(message, false), false));
                    folded = message.content().size();
                } else {
                    List<ContentBlock> merged = new ArrayList<>(last.content());
                    if (last.content().stream().anyMatch(ToolResultBlock.class::isInstance)) {
                        merged.addAll(modelChanges(message, false));
                    } else {
                        // 写在用户的话前面时空一行隔开（OpenAI 兼容的把文字段直接拼在一起）
                        List<ContentBlock> notices = modelChanges(message, true);
                        merged.addAll(folded, notices);
                        folded += notices.size();
                    }
                    out.set(out.size() - 1, new Message(Role.USER, merged, last.interrupted()));
                }
                afterHookTexts = false;
                continue;
            }
            if (hookTexts && SystemUpdates.isSystemUpdate(message)) {
                folded = 0;
                afterHookTexts = true;
                List<ContentBlock> notices = new ArrayList<>();
                for (ContentBlock block : message.content()) {
                    if (block instanceof HookTextBlock note) {
                        notices.add(new TextBlock(SystemUpdates.HEADER + note.text()));
                    }
                }
                if (notices.isEmpty()) {
                    continue;
                }
                Message last = out.isEmpty() ? null : out.getLast();
                if (last == null || last.role() != Role.USER) {
                    out.add(new Message(Role.USER, notices, false));
                    continue;
                }
                List<ContentBlock> merged = new ArrayList<>(last.content());
                merged.addAll(notices);
                out.set(out.size() - 1, new Message(Role.USER, merged, last.interrupted()));
                continue;
            }
            out.add(message);
            folded = 0;
            afterHookTexts = false;
        }
        return out;
    }

    private static List<ContentBlock> modelChanges(Message message, boolean before) {
        return message.content().stream()
                .map(block -> (ContentBlock) new TextBlock(((ModelChangeBlock) block).text() + (before ? "\n\n" : ""))).toList();
    }
}
