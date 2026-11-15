package tantalum.memory.capture;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import tantalum.memory.ledger.MemoryItem;
import tantalum.shell.plugin.RunSegment;

/**
 * 原文核对（memory-final-plan 第 4、7 节）：模型给的引用必须在对应角色的分段里逐字找得到（先规范化：NFKC、小写、去掉空白和标点），
 * 在你的话里 → 你亲口说的；在助手的回答里 → 推断；都找不到 → 丢掉。工具的分段不算（文件里写的“User: …”冒充不了你），
 * 只有问答卡（{@code ask_user}）的回答算你的话。
 */
public final class Quotes {

    /** 引用至少这么长（规范化后的字符数）；含汉字、假名时两个字就够。 */
    static final int MIN_LATIN = 4;
    static final int MIN_CJK = 2;

    private Quotes() {}

    /** 引用落在哪：你的话、回答，或者哪里都没有（null）。 */
    public static MemoryItem.Source find(String quote, List<RunSegment> segments) {
        String wanted = normalize(quote);
        if (wanted.length() < (cjk(wanted) ? MIN_CJK : MIN_LATIN)) {
            return null;
        }
        for (RunSegment segment : segments) {
            if (userWords(segment) && normalize(segment.text()).contains(wanted)) {
                return MemoryItem.Source.USER_SAID;
            }
        }
        for (RunSegment segment : segments) {
            if (segment.role() == RunSegment.Role.ASSISTANT && normalize(segment.text()).contains(wanted)) {
                return MemoryItem.Source.ASSISTANT_INFERRED;
            }
        }
        return null;
    }

    /** 不算“外部内容”的工具：问答卡（你的回答）、待办清单（模型自己的计划）；记忆插件自己的工具也不算。 */
    public static final java.util.Set<String> TRUSTED_TOOLS = java.util.Set.of("ask_user", "todo_write");

    /** 这一轮读过外部内容：调过除问答卡、待办清单和记忆自己的工具以外的工具（memory-final-plan 第 7 节）。 */
    public static boolean tainted(List<RunSegment> segments) {
        return segments.stream().anyMatch(segment -> segment.role() == RunSegment.Role.TOOL && !TRUSTED_TOOLS.contains(segment.tool())
                && !tantalum.memory.MemoryTools.PLUGIN.equals(segment.plugin()));
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
