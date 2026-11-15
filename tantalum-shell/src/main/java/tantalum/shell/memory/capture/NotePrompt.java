package tantalum.shell.memory.capture;

import java.util.List;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.Prompt;

/**
 * 记笔记的提示词（memory-capture 需求 1.2、1.3）。system 固定不变（能命中缓存），照 ReMe auto_memory 的取舍：
 * 记用户的事实和偏好、决定和原因、进展和下一步、可复用的做法、用户的纠正；闲聊和一次性的细节不记；没有值得记的回 {@link #NOTHING}。
 */
public final class NotePrompt {

    public static final String NOTHING = "NOTHING";
    public static final int MAX_BULLETS = 6;

    static final String SYSTEM = """
            You keep notes for a user's long-term memory. You are given one turn of a conversation between the user and an \
            AI assistant: what the user said, the tools the assistant used, and its final answer.
            Write down what will still matter in a later conversation:
            - facts about the user and their preferences
            - decisions made and why
            - progress on the user's work and the next steps
            - procedures that worked and can be reused
            - corrections the user made to the assistant
            Do not write down small talk, the details of a one-off task, or anything already obvious from a single message.
            Do not copy the content of files, web pages or the assistant's answer: material the user can read again is not worth \
            keeping. Note what the user cares about or wants to do with it instead, in one bullet at most. Do not describe what the \
            assistant did (for example that it saved something).
            Write 1 to 6 short bullet points starting with "- ", in the language the user wrote in, with no headings and no \
            preamble. Never include passwords, API keys, tokens or other secrets.
            If nothing is worth keeping, reply exactly NOTHING.

            Examples:
            - The user asks to read a file and say what one section is about, and gives no reason: NOTHING. The file is still there.
            - The user asks to read a file because they are preparing a talk on it next week: one bullet, "Preparing a talk on \
            <topic> for next week".
            - The user says "always answer in English": one bullet, "Wants answers in English".
            """;

    private NotePrompt() {}

    /** {@code title} 是会话标题，{@code day} 是今天的日期，{@code excerpt} 来自 {@link RunExcerpt}。 */
    public static Prompt of(String title, String day, String excerpt, long maxTokens, ModelChoice model) {
        // 用标签框住这一轮，最后明确要它写笔记：不然模型会把自己当成对话里的助手，接着回答（本机 qwen 实测）
        String user = "Conversation: " + (title == null || title.isBlank() ? "(untitled)" : title) + "\nDate: " + day
                + "\n\n<turn>\n" + excerpt + "\n</turn>\n\nYou are not in this conversation. Write the notes about it now: "
                + "bullet points starting with \"- \", or NOTHING.";
        return new Prompt(SYSTEM, List.of(), List.of(Message.user(user)), new Prompt.Output(false, maxTokens), model);
    }

    /** 模型的回答里的要点（“- ”“* ”“• ” 开头的行，最多 {@link #MAX_BULLETS} 条）；回答是 {@link #NOTHING} 或没有要点时为空。 */
    public static List<String> bullets(String reply) {
        if (reply == null || reply.strip().equalsIgnoreCase(NOTHING)) {
            return List.of();
        }
        // 最多 MAX_BULLETS 条：模型有时把文件或回答整段抄进来（实测读文件的一轮写了 15 条）；以冒号结尾的半截话不要
        return reply.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.equalsIgnoreCase(NOTHING))
                .filter(line -> !line.startsWith("#"))
                .filter(line -> !line.endsWith("：") && !line.endsWith(":"))
                .filter(line -> !line.startsWith("```") && !line.replaceFirst("^[-*•]\\s*", "").startsWith("```"))
                .filter(line -> line.replaceFirst("^[-*•]\\s*", "").strip().length() >= 4)
                .limit(MAX_BULLETS)
                // 不是要点的行（模型接着对话回答了）不要
                .filter(line -> line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• "))
                .map(line -> line.startsWith("- ") ? line : "- " + line.substring(2).strip())
                .toList();
    }
}
