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
 *
 * <p>工作的现状（文件里现在是什么、测试过没过、这一轮改了哪些文件）不记：它在文件和对话原话里，会变，记下来就过时
 * （2026-11-16 Sonnet 五天故事：15 句话记出 11 条“待确认”，多半是“附加费现在是 12”这类）。工作记忆里还开着的事项随这一轮一起给模型，
 * 这一轮里办完了、不做了的，模型写 {@code - [done] 编号 «原话»}，由 {@link NoteFiler} 关掉。
 */
public final class NotePrompt {

    public static final String NOTHING = "NOTHING";
    public static final int MAX_BULLETS = 6;

    /** 一条笔记：种类、要点、模型给的原话；{@code replaces} 是模型写明它取代的那条已有说法的编号，没有为 null。 */
    public record Note(Kind kind, String text, String quote, String replaces) {

    }

    /** 笔记的种类：前三种进页面，中间三种进工作记忆，“笔记”只进当天卡；“了结”不是新笔记，是关掉一项开着的事（要点是那一项的编号）。 */
    public enum Kind {
        PREFERENCE, FACT, DECISION, TASK, COMMITMENT, WAITING, NOTE, DONE;

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
            - work that is still unfinished when this turn ends and has to be picked up in a later conversation, with its \
            next step (task). Work that was asked for and finished in this turn is not a task.
            - something promised: who promised what to whom, by when (commitment)
            - something the user or the assistant is waiting for (waiting)
            - anything else worth keeping (note)
            Do not write down small talk, the details of a one-off task, or anything already obvious from a single message. \
            Do not describe what the assistant did (for example that it saved something). Material the user can read again, \
            like the content of a file or a web page, is not worth keeping.
            The system itself keeps track of a question the assistant asked and is waiting to have answered. Do not note \
            it as waiting, task or commitment; it is closed by the system, and a note about it would stay open forever. \
            "waiting" is only for something outside: a person who has to reply, a date, an event.
            The state of the work is not memory either: what a file contains, what a value is right now, whether tests pass \
            or fail, which files were changed, what was found while looking. All of that can be read again from the files \
            and from the conversation history, and it changes, so a note about it goes stale. Do not write it down as fact, \
            decision or task. What the user decided or asked for is memory: write it so that it stays true ("express \
            surcharge: the user decided on 15"), not as the state of a file ("the surcharge is now 15 on line 4").
            The turn may come with a list of open items: things already in the user's working memory, each with an id. Do \
            not write an open item again as a new note. If this turn shows that an open item is finished, cancelled or no \
            longer true, close it with a line like this:
            - [done] id «exact words»
            where id is the item's id from the list and «exact words» is copied from the turn and shows it is over.
            The turn may also come with a list of known entries: preferences, facts and decisions already in the user's \
            memory, each with an id. Do not write a known entry again, in the same or in other words; if this turn only \
            repeats it, write nothing for it. If this turn changes a known entry (the user changed their mind, corrected \
            it, or decided otherwise), write the new note and end the line with (replaces id), where id is the id of the \
            entry it takes the place of:
            - [preference] the new note «exact words» (replaces id)
            Use it only when the new note and the known entry cannot both hold; a note that adds to an entry replaces nothing.
            Write each note on its own line, like this:
            - [kind] the note «exact words»
            where kind is one of preference, fact, decision, task, commitment, waiting, note, and «exact words» is a short \
            piece copied character for character from what the user said or from the assistant's answer that shows the note \
            is true. When something the user said shows it, copy the user's words, not the assistant's; copy from the \
            answer only when nothing the user said shows it. A note whose words cannot be found in the turn is thrown away, \
            so copy them exactly.
            Say in the note only what the copied words say, in the user's own wording where you can. When the turn used \
            tools, a note counts as the user's own statement only if the note itself is the user's exact words; any other \
            note from such a turn is kept for the user to confirm.
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
            "^[-*•]\\s*(?:\\[([A-Za-z]+)\\]\\s*)?(.+?)\\s*(?:«([^»]+)»|“([^”]+)”|\"([^\"]+)\")?\\s*(?:[(（]replaces\\s+([A-Za-z0-9_-]+)[)）])?\\s*[。.]?$");

    private NotePrompt() {}

    /** 给模型的 system。 */
    public static String system() {
        return SYSTEM;
    }

    /** 工作记忆里还开着的一项：编号和它的内容。 */
    public record Open(String id, String text) {}

    /** 记忆里已有的一条说法：编号、种类（preference、fact、decision）、内容、是不是你认可过的、是不是还在等你确认。 */
    public record Known(String id, String kind, String text, boolean approved, boolean pending) {}

    /** {@code title} 是会话标题，{@code day} 是今天的日期，{@code turn} 是这一轮你说的话和最后的回答。 */
    public static String user(String title, String day, String turn) {
        return user(title, day, turn, List.of());
    }

    /** 同上，{@code open} 是工作记忆里还开着的事项（没有就不写这一段）。 */
    public static String user(String title, String day, String turn, List<Open> open) {
        return user(title, day, turn, open, List.of());
    }

    /** 同上，{@code known} 是记忆里已有的说法（没有就不写这一段）。 */
    public static String user(String title, String day, String turn, List<Open> open, List<Known> known) {
        StringBuilder items = new StringBuilder();
        if (!open.isEmpty()) {
            items.append("\n\n<open-items>\n");
            open.forEach(item -> items.append("- ").append(item.id()).append(": ").append(item.text().replaceAll("\\s+", " ").strip()).append('\n'));
            items.append("</open-items>");
        }
        if (!known.isEmpty()) {
            items.append("\n\n<known-entries>\n");
            known.forEach(entry -> items.append("- ").append(entry.id()).append(" [").append(entry.kind()).append("]: ")
                    .append(entry.text().replaceAll("\\s+", " ").strip())
                    .append(entry.approved() ? " (approved by the user)" : entry.pending() ? " (waiting for the user to confirm)" : "").append('\n'));
            items.append("</known-entries>");
        }
        // 用标签框住这一轮，最后明确要它写笔记：不然模型会把自己当成对话里的助手，接着回答（本机 qwen 实测）
        return "Conversation: " + (title == null || title.isBlank() ? "(untitled)" : title) + "\nDate: " + day
                + "\n\n<turn>\n" + turn + "\n</turn>" + items + "\n\nYou are not in this conversation. Write the notes about it now: "
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
            Kind kind = Kind.parse(matcher.group(1));
            if (kind == Kind.DONE) {
                // 关掉一项：要点只留编号（模型有时在后面接着抄那一项的内容）
                text = text.split("[\\s:：]", 2)[0].replaceAll("^[\\[(]|[\\])]$", "");
                if (matcher.group(3) == null && matcher.group(4) == null && matcher.group(5) == null) {
                    continue;
                }
            }
            notes.add(new Note(kind, text, quote.strip(), kind == Kind.DONE ? null : matcher.group(6)));
        }
        return notes;
    }
}
