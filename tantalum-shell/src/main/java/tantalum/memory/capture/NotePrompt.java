package tantalum.memory.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记笔记的提示词（memory-capture 需求 1.2、1.3；memory-final-plan 第 7 节）。system 固定不变（能命中缓存）。模型只看到你说的话和最后的回答
 * （工具的输出不给），每条笔记一行：{@code - [种类] 要点 «原话»}。原话由代码在对应的分段里核对（{@link Quotes}），对不上的整条丢掉。
 * 没有值得记的回 {@link #NOTHING}。
 */
public final class NotePrompt {

    public static final String NOTHING = "NOTHING";
    public static final int MAX_BULLETS = 6;

    /** 一条笔记：种类、要点、模型给的原话。 */
    public record Note(Kind kind, String text, String quote) {}

    /** 笔记的种类：前三种进页面，中间三种进工作记忆，最后一种只进当天卡。 */
    public enum Kind {
        PREFERENCE, FACT, DECISION, TASK, COMMITMENT, WAITING, NOTE;

        static Kind parse(String word) {
            if (word == null) {
                return NOTE;
            }
            try {
                return valueOf(word.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return NOTE;
            }
        }
    }

    static final String SYSTEM = """
            You keep notes for a user's long-term memory. You are given one turn of a conversation between the user and an \
            AI assistant: what the user said and the assistant's final answer.
            Write down what will still matter in a later conversation:
            - how the user wants things done (preference)
            - facts about the user, their work, and the people and things they deal with (fact)
            - decisions made and why (decision)
            - work in progress and its next step (task)
            - something promised: who promised what to whom, by when (commitment)
            - something the user or the assistant is waiting for (waiting)
            - anything else worth keeping (note)
            Do not write down small talk, the details of a one-off task, or anything already obvious from a single message. \
            Do not describe what the assistant did (for example that it saved something). Material the user can read again, \
            like the content of a file or a web page, is not worth keeping.
            Write each note on its own line, like this:
            - [kind] the note «exact words»
            where kind is one of preference, fact, decision, task, commitment, waiting, note, and «exact words» is a short \
            piece copied character for character from what the user said or from the assistant's answer that shows the note \
            is true. A note whose words cannot be found in the turn is thrown away, so copy them exactly.
            Write the notes in the language the user wrote in. At most 6 lines, no headings, no preamble. Never include \
            passwords, API keys, tokens, ID numbers or phone numbers.
            If nothing is worth keeping, reply exactly NOTHING.

            Examples:
            - The user says "以后用简体中文、尽量简洁": - [preference] 回答用简体中文，尽量简洁 «以后用简体中文、尽量简洁»
            - The user says "the release moves to December 1 because the payment review slipped": \
            - [decision] Release moved to December 1 because the payment review slipped «the release moves to December 1»
            - The user asks to read a file and say what one section is about, and gives no reason: NOTHING.
            """;

    private static final Pattern LINE = Pattern.compile(
            "^[-*•]\\s*(?:\\[([A-Za-z]+)\\]\\s*)?(.+?)\\s*(?:«([^»]+)»|“([^”]+)”|\"([^\"]+)\")?\\s*[。.]?$");

    private NotePrompt() {}

    /** 给模型的 system。 */
    public static String system() {
        return SYSTEM;
    }

    /** {@code title} 是会话标题，{@code day} 是今天的日期，{@code turn} 是这一轮你说的话和最后的回答。 */
    public static String user(String title, String day, String turn) {
        // 用标签框住这一轮，最后明确要它写笔记：不然模型会把自己当成对话里的助手，接着回答（本机 qwen 实测）
        return "Conversation: " + (title == null || title.isBlank() ? "(untitled)" : title) + "\nDate: " + day
                + "\n\n<turn>\n" + turn + "\n</turn>\n\nYou are not in this conversation. Write the notes about it now: "
                + "lines like \"- [kind] the note «exact words»\", or NOTHING.";
    }

    /**
     * 回答里的笔记，最多 {@link #MAX_BULLETS} 条。没给原话的（本机 qwen 实测 24 行里有 7 行）拿要点本身当原话去核对：
     * 要点逐字出现在你的话里才算，否则照样丢掉。回答是 {@link #NOTHING} 时为空。
     */
    public static List<Note> notes(String reply) {
        List<Note> notes = new ArrayList<>();
        if (reply == null || reply.strip().equalsIgnoreCase(NOTHING)) {
            return notes;
        }
        for (String line : reply.lines().map(String::strip).toList()) {
            if (notes.size() >= MAX_BULLETS) {
                break;
            }
            Matcher matcher = LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String text = matcher.group(2).strip();
            String quote = matcher.group(3) != null ? matcher.group(3) : matcher.group(4) != null ? matcher.group(4) : matcher.group(5);
            if (quote == null || quote.isBlank()) {
                quote = text;
            }
            if (text.length() < 2) {
                continue;
            }
            notes.add(new Note(Kind.parse(matcher.group(1)), text, quote.strip()));
        }
        return notes;
    }
}
