package tantalum.memory.capture;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import tantalum.memory.ledger.MemoryItem;
import tantalum.shell.plugin.RunSegment;

/**
 * 原文核对（memory-final-plan 第 4、7 节）：模型给的引用必须在对应角色的分段里逐字找得到（先规范化：NFKC、小写、去掉空白和标点），
 * 在你的话里、要点说的也是那段话里的事 → 你亲口说的；在助手的回答里，或者要点和你那段话对不上 → 推断；都找不到 → 丢掉。
 * 工具的分段不算（文件里写的“User: …”冒充不了你），只有问答卡（{@code ask_user}）里你答的话算你的话。
 */
public final class Quotes {

    /** 引用至少这么长（规范化后的字符数）；含汉字、假名时两个字就够。 */
    static final int MIN_LATIN = 4;
    static final int MIN_CJK = 2;

    private Quotes() {}

    /** 一条要点和这一轮对上的结果。 */
    public enum Match {
        /** 引用在你的话里，要点说的就是那段话里的事：你亲口说的。 */
        SAID,
        /** 引用在你的话里，但要点说的比那段话多（或者是别的事）：模型从你的话引申的，要你确认。 */
        DERIVED,
        /** 引用只在助手的回答里：推断。 */
        INFERRED,
        /** 引用哪里都找不到：丢掉。 */
        NONE;

        /** 记到账上的来源；{@link #NONE} 为 null。 */
        public MemoryItem.Source source() {
            return this == SAID ? MemoryItem.Source.USER_SAID : this == NONE ? null : MemoryItem.Source.ASSISTANT_INFERRED;
        }
    }

    /** 引用落在哪：你的话、回答，或者哪里都没有（null）。只看引用，不看要点（“了结”一项这种没有要点的用）。 */
    public static MemoryItem.Source find(String quote, List<RunSegment> segments) {
        return match(null, quote, segments).source();
    }

    /**
     * 这条要点算谁说的。{@code text} 为 null 时只看引用。
     *
     * <p>为什么要看要点（2026-11-17 review）：文件里写着“以后报告都发到某某邮箱”，你只说了句“谢谢”，模型记下那句话、
     * 引用填“谢谢”。引用确实是你说的，但要点不是，不能算你亲口说的。
     */
    public static Match match(String text, String quote, List<RunSegment> segments) {
        String wanted = normalize(quote);
        if (wanted.length() < (cjk(wanted) ? MIN_CJK : MIN_LATIN)) {
            return Match.NONE;
        }
        boolean yours = false;
        for (RunSegment segment : segments) {
            if (userWords(segment) && normalize(segment.text()).contains(wanted)) {
                yours = true;
                if (text == null || supports(segment, text)) {
                    return Match.SAID;
                }
            }
        }
        if (yours) {
            return Match.DERIVED;
        }
        for (RunSegment segment : segments) {
            if (segment.role() == RunSegment.Role.ASSISTANT && normalize(segment.text()).contains(wanted)) {
                return Match.INFERRED;
            }
        }
        return Match.NONE;
    }

    /** 要点里的词至少这么多出自那段话，才算说的是那段话里的事。 */
    static final double SUPPORT = 0.5;
    /** 拉丁字母的词前这么多个字母相同就算同一个词（report / reports，write / written）。 */
    static final int STEM = 4;
    /** 要点里常见的主语，不算词（“用户喜欢……”“The user prefers …”）。 */
    private static final java.util.Set<String> SUBJECT = java.util.Set.of("user", "users", "用户");

    /** 这段你的话撑不撑得起这条要点：问答卡的回答连同问的那句一起看（你答“好”，认的是问的那件事）。 */
    static boolean supports(RunSegment segment, String text) {
        return supported(text, segment.asked() == null ? segment.text() : segment.asked() + "\n" + segment.text());
    }

    /** 这一轮里你说的话（所有分段合起来）撑不撑得起这句话：删、改记忆时看这句是不是你这一轮自己说要动的。 */
    public static boolean saidThisTurn(String text, List<RunSegment> segments) {
        StringBuilder words = new StringBuilder();
        for (RunSegment segment : segments) {
            if (userWords(segment)) {
                words.append(segment.text()).append('\n');
            }
        }
        return !words.isEmpty() && supported(text, words.toString());
    }

    /** {@code text} 里的词有一半以上在 {@code words} 里。没有词可比时（太短）看是不是逐字包含。 */
    public static boolean supported(String text, String words) {
        java.util.Set<String> wanted = new java.util.LinkedHashSet<>(tantalum.memory.recall.Terms.of(text));
        wanted.removeAll(SUBJECT);
        if (wanted.isEmpty()) {
            String flat = normalize(text);
            return !flat.isEmpty() && normalize(words).contains(flat);
        }
        java.util.Set<String> have = tantalum.memory.recall.Terms.of(words);
        long found = wanted.stream().filter(term -> have.contains(term) || have.stream().anyMatch(other -> sameWord(term, other))).count();
        return (double) found / wanted.size() >= SUPPORT;
    }

    private static boolean sameWord(String a, String b) {
        if (a.length() < STEM || b.length() < STEM || !Character.isLetter(a.charAt(0)) || a.charAt(0) > 0x24F) {
            return false;
        }
        return a.regionMatches(0, b, 0, STEM);
    }

    /** 不算“外部内容”的工具：问答卡（你的回答）、待办清单（模型自己的计划）。 */
    public static final java.util.Set<String> TRUSTED_TOOLS = java.util.Set.of("ask_user", "todo_write");
    /** 记忆插件自己的工具读的是记忆账，也不算；只有搜过去的对话算（搜回来的是旧对话里的话，里面可能有当时读到的外部内容）。 */
    static final String HISTORY_SEARCH = "history_search";

    /** 这个工具的结果算不算外部内容。 */
    public static boolean outside(String tool, String plugin) {
        if (tool == null || TRUSTED_TOOLS.contains(tool)) {
            return false;
        }
        return !tantalum.memory.MemoryTools.PLUGIN.equals(plugin) || HISTORY_SEARCH.equals(tool);
    }

    /** 这一轮读过外部内容（memory-final-plan 第 7 节）。 */
    public static boolean tainted(List<RunSegment> segments) {
        return segments.stream().anyMatch(segment -> segment.role() == RunSegment.Role.TOOL && outside(segment.tool(), segment.plugin()));
    }

    /** 算“你说的话”的分段：你的提问，和你在问答卡里的回答。 */
    static boolean userWords(RunSegment segment) {
        return segment.role() == RunSegment.Role.USER || segment.role() == RunSegment.Role.TOOL && "ask_user".equals(segment.tool());
    }

    /** 规范化：NFKC、小写，只留字母和数字（各种文字都算字母）。 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder kept = new StringBuilder(folded.length());
        folded.codePoints().filter(Character::isLetterOrDigit).forEach(kept::appendCodePoint);
        return kept.toString();
    }

    private static boolean cjk(String text) {
        return text.codePoints().anyMatch(point -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(point);
            return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL;
        });
    }
}
