package tantalum.shell.model;

import java.util.ArrayList;
import java.util.List;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.HookTextBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolChangeBlock;

/**
 * 不支持会话中途 system 消息的 Claude 模型（Sonnet 5）怎么收钩子的说明（hook-patch 需求 3.2）：并进它前面那条用户消息的末尾，
 * 每段前面加“不是用户写的”前缀（Claude 文档的 system-reminder 降级；DSH {@code llm-pi-ai/src/context.ts:229-238} 同）。
 * 同一条消息里的工具变化照现有规则丢掉（这类模型不发工具变化）。钩子的说明总是紧跟它所并入的用户消息，两者在同一次调用里第一次发出，
 * 所以并进去不改任何已发出的字节。前面不是用户消息时单独成一条。纯函数，每次发出的字节相同。
 */
final class HookTexts {

    private HookTexts() {}

    static List<Message> fold(List<Message> messages) {
        if (messages.stream().noneMatch(HookTexts::isUpdate)) {
            return messages;
        }
        List<Message> out = new ArrayList<>();
        for (Message message : messages) {
            if (!isUpdate(message)) {
                out.add(message);
                continue;
            }
            List<ContentBlock> notices = new ArrayList<>();
            for (ContentBlock block : message.content()) {
                if (block instanceof HookTextBlock note) {
                    notices.add(new TextBlock(ToolChanges.PREFIX + note.text()));
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
        }
        return out;
    }

    /** 工具变化或钩子说明的系统消息（和内核 {@code PromptProjection.isSystemUpdate} 同一个定义）。 */
    static boolean isUpdate(Message message) {
        return message.role() == Role.SYSTEM && !message.content().isEmpty()
                && message.content().stream().allMatch(block -> block instanceof ToolChangeBlock || block instanceof HookTextBlock);
    }
}
