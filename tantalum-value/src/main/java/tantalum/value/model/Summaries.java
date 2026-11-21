package tantalum.value.model;

/**
 * 压缩写下的摘要：替换段里的第一条，一条用户角色的消息，开头写明不是用户写的。它不是你说的话（{@link Message#fromUser()}），
 * 找回记忆、记笔记、标题都不拿它当你的话。内核写它（{@code SummaryPrompt}），这里只认它。
 */
public interface Summaries {

    String PREFIX = "[Summary of the earlier conversation, not written by the user. Continue the task directly from it; do not acknowledge it.]\n\n";

    static boolean isSummary(Message message) {
        return message.content().size() == 1 && message.content().getFirst() instanceof TextBlock text && text.text().startsWith(PREFIX);
    }
}
