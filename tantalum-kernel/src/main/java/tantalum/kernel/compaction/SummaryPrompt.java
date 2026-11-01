package tantalum.kernel.compaction;

import java.util.List;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;

/** 摘要指令（规则 6，结构来自 pi）和摘要校验（规则 7）。 */
public final class SummaryPrompt {

    /** 摘要消息的开头，模型和下一次压缩都靠它认出这是摘要。 */
    public static final String SUMMARY_PREFIX = "[Summary of the earlier conversation, not written by the user.]\n\n";

    private static final String INSTRUCTION = """
            The conversation above is about to be replaced by a summary so that work can continue in a smaller context. \
            Write that summary now. It is the only memory of this part of the conversation, so be specific.

            Use exactly these sections:

            ## Goal
            What the user is trying to achieve, in their terms.

            ## Constraints & preferences
            Rules the user gave, things to avoid, style or tool preferences. Keep every one, even if it seemed minor.

            ## Progress
            ### Done
            ### In progress
            ### Blocked

            ## Key decisions
            Decisions made and why, including alternatives that were rejected.

            ## Next steps
            What should happen next, in order.

            ## Key context
            Facts needed to continue: file paths, function and variable names, commands, error messages, numbers, identifiers. \
            Copy them exactly as they appeared; do not paraphrase them.

            If the conversation already starts with an earlier summary, keep everything in it that is still true and update it; \
            do not drop constraints or context from it.

            Do not call any tool. Do not add anything that is not in the conversation. Do not address the user; write notes for yourself.""";

    private SummaryPrompt() {}

    /** {@code dropped} 为真时说明最早的部分放不下、已被省略；{@code manual} 是用户的附加说明，可为 null。 */
    public static Message instruction(boolean dropped, String manual) {
        StringBuilder text = new StringBuilder(INSTRUCTION);
        if (dropped) {
            text.append("\n\nNote: the oldest part of the conversation did not fit and has been omitted above; "
                    + "say so in Key context if anything seems to be missing.");
        }
        if (manual != null && !manual.isBlank()) {
            text.append("\n\nThe user asked you to pay particular attention to the following when summarizing:\n").append(manual.strip());
        }
        return Message.user(text.toString());
    }

    /** 摘要消息：替换视图的第一条。 */
    public static Message summaryMessage(String summary) {
        return Message.user(SUMMARY_PREFIX + summary.strip());
    }

    public static boolean isSummary(Message message) {
        return message.content().size() == 1 && message.content().getFirst() instanceof TextBlock text
                && text.text().startsWith(SUMMARY_PREFIX);
    }

    /**
     * 规则 7。通过返回 null，否则返回原因。{@code headEstimate} 是头部的估算，摘要不能比它大。
     */
    public static String validate(List<ContentBlock> content, StopReason stop, long headEstimate) {
        if (content.stream().anyMatch(ToolCallBlock.class::isInstance)) {
            return "summary tried to call a tool";
        }
        if (stop != StopReason.END_TURN) {
            return "summary did not end normally: " + stop;
        }
        String text = text(content);
        if (text.isBlank()) {
            return "summary is empty";
        }
        if (TokenEstimate.ofMessage(summaryMessage(text)) >= headEstimate) {
            return "summary is not smaller than what it replaces";
        }
        return null;
    }

    public static String text(List<ContentBlock> content) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.text());
            }
        }
        return text.toString();
    }
}
