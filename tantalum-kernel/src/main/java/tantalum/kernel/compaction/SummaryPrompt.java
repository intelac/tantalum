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
    public static final String SUMMARY_PREFIX = "[Summary of the earlier conversation, not written by the user. Continue the task directly from it; do not acknowledge it.]\n\n";

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

            ## Files and code
            Files read or changed and what matters in them, with the exact names and snippets needed to continue.

            ## Errors and fixes
            Errors met and how they were fixed. Whenever the user corrected you, record what they corrected, faithfully.

            ## Next steps
            What should happen next, in order.

            ## Key context
            Facts needed to continue: file paths, function and variable names, commands, error messages, numbers, identifiers. \
            Copy them exactly as they appeared; do not paraphrase them.

            If the conversation already starts with an earlier summary, keep everything in it that is still true and update it: \
            move finished items from In progress to Done, and do not drop constraints or context from it.

            Do not call any tool. Do not add anything that is not in the conversation. Do not address the user; write notes for yourself.""";

    static final int MAX_FILES = 200;
    private static final String FILES_OPEN = "<files-touched>";
    private static final String FILES_CLOSE = "</files-touched>";

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
        return summaryMessage(summary, List.of());
    }

    /** {@code files} 由程序提取，追加在末尾，不靠模型记住。 */
    public static Message summaryMessage(String summary, List<String> files) {
        String list = files.isEmpty() ? "" : "\n\n" + FILES_OPEN + "\n" + String.join("\n", files) + "\n" + FILES_CLOSE;
        return Message.user(SUMMARY_PREFIX + summary.strip() + list);
    }

    /** 摘要消息末尾的文件列表；模型自己写的同名标签不算，只认最后一个。 */
    public static List<String> filesIn(Message summary) {
        if (!isSummary(summary)) {
            return List.of();
        }
        String text = ((TextBlock) summary.content().getFirst()).text();
        int open = text.lastIndexOf(FILES_OPEN);
        int close = text.lastIndexOf(FILES_CLOSE);
        if (open < 0 || close < open) {
            return List.of();
        }
        return text.substring(open + FILES_OPEN.length(), close).strip().lines().filter(line -> !line.isBlank()).toList();
    }

    /** 最多保留最近的 {@value #MAX_FILES} 条。 */
    public static List<String> capFiles(List<String> files) {
        return files.size() <= MAX_FILES ? List.copyOf(files) : List.copyOf(files.subList(files.size() - MAX_FILES, files.size()));
    }

    public static boolean isSummary(Message message) {
        return message.content().size() == 1 && message.content().getFirst() instanceof TextBlock text
                && text.text().startsWith(SUMMARY_PREFIX);
    }

    /**
     * 规则 7。通过返回 null，否则返回原因。替代头部的是摘要加上原样重复的当前请求（{@code requestEstimate}，没有则为 0），
     * 两者合起来必须比头部（{@code headEstimate}）小，否则压缩只会让视图变大。三者用同一个 {@code estimate} 算。
     */
    public static String validate(List<ContentBlock> content, StopReason stop, long headEstimate, long requestEstimate,
                                  TokenEstimate estimate) {
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
        if (estimate.ofMessage(summaryMessage(text)) + requestEstimate >= headEstimate) {
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
