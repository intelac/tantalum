package tantalum.memory.recall;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 检索用的词（memory-final-plan 第 9 节：中日韩切成两字一组，在 Java 里预处理）：NFKC、小写；拉丁字母和数字按词（至少两个字符），
 * 汉字、假名、谚文按相邻两字一组（只有一个字时就是这个字）。含常见虚字（的了吗呢……）的两字组和英文虚词不要。
 */
public final class Terms {

    /** 汉语里只起语法作用的字：含它们的两字组不当关键词。 */
    static final String STOP_CHARS = "的了么吗呢吧啊呀哦我你他她它们是这那什怎哪谁着过和与及或就都也还在有个把被给让请";
    static final Set<String> STOP_WORDS = Set.of("the", "a", "an", "is", "are", "was", "were", "be", "do", "does", "did", "what", "which",
            "who", "when", "where", "how", "why", "we", "you", "i", "my", "our", "your", "it", "this", "that", "to", "of", "in", "on",
            "for", "and", "or", "with", "about", "again", "please", "tell", "me", "us", "there", "any", "can", "could", "would",
            "should", "will", "run", "runs", "right");

    private Terms() {}

    /** 一段文字里的词，按出现顺序去重。 */
    public static Set<String> of(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        folded.codePoints().forEach(point -> {
            if (isCjk(point)) {
                flushLatin(latin, terms);
                cjk.appendCodePoint(point);
            } else if (Character.isLetterOrDigit(point)) {
                flushCjk(cjk, terms);
                latin.appendCodePoint(point);
            } else {
                flushLatin(latin, terms);
                flushCjk(cjk, terms);
            }
        });
        flushLatin(latin, terms);
        flushCjk(cjk, terms);
        return terms;
    }

    private static void flushLatin(StringBuilder word, Set<String> terms) {
        if (word.length() >= 2 && !STOP_WORDS.contains(word.toString())) {
            terms.add(word.toString());
        }
        word.setLength(0);
    }

    private static void flushCjk(StringBuilder run, Set<String> terms) {
        int[] points = run.codePoints().toArray();
        if (points.length == 1 && STOP_CHARS.indexOf(points[0]) < 0) {
            terms.add(new String(points, 0, 1));
        }
        for (int i = 0; i + 1 < points.length; i++) {
            if (STOP_CHARS.indexOf(points[i]) < 0 && STOP_CHARS.indexOf(points[i + 1]) < 0) {
                terms.add(new String(points, i, 2));
            }
        }
        run.setLength(0);
    }

    static boolean isCjk(int point) {
        Character.UnicodeScript script = Character.UnicodeScript.of(point);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL;
    }
}
