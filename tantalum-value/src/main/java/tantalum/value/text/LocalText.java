package tantalum.value.text;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 插件作者写给人看的文字（console-i18n I10，照 DSH {@code LocalizedText}）：一个字符串，或者英、中、日三种，英文必填。
 * 控制台按“用户的语言 → 英文”选；只写了一个字符串的不知道是哪种语言，照原样显示。
 * 写成 JSON 时一个字符串就是字符串，带语言的是 {@code {"en": …, "zh": …, "ja": …}}，见 {@link #wire()}。
 */
public record LocalText(String plain, Map<Language, String> byLanguage) {

    private static final LocalText EMPTY = new LocalText("", Map.of());
    /** 英文在最前，和 DSH 的写法一样。 */
    private static final List<Language> WIRE_ORDER = List.of(Language.EN, Language.ZH, Language.JA);

    public LocalText {
        Objects.requireNonNull(plain, "plain");
        byLanguage = byLanguage.isEmpty() ? Map.of() : Map.copyOf(byLanguage);
        if (!byLanguage.isEmpty() && byLanguage.get(Language.EN) == null) {
            throw new IllegalArgumentException("LocalText with languages must have English");
        }
    }

    public static LocalText empty() {
        return EMPTY;
    }

    /** 作者只写了一种文字。 */
    public static LocalText of(String text) {
        return text.isEmpty() ? EMPTY : new LocalText(text, Map.of());
    }

    public static LocalText of(String en, String zh, String ja) {
        return en(en).zh(zh).ja(ja);
    }

    /** 从英文开始，再接 {@link #zh}、{@link #ja}。 */
    public static LocalText en(String text) {
        return new LocalText("", Map.of(Language.EN, text));
    }

    public LocalText zh(String text) {
        return with(Language.ZH, text);
    }

    public LocalText ja(String text) {
        return with(Language.JA, text);
    }

    private LocalText with(Language language, String text) {
        if (byLanguage.isEmpty()) {
            throw new IllegalStateException("start with LocalText.en(...)");
        }
        Map<Language, String> next = new EnumMap<>(byLanguage);
        next.put(language, text);
        return new LocalText("", next);
    }

    public boolean isEmpty() {
        return byLanguage.isEmpty() && plain.isEmpty();
    }

    /** 这种语言的文字；没有就用英文，只有一个字符串的照原样。 */
    public String in(Language language) {
        if (byLanguage.isEmpty()) {
            return plain;
        }
        return byLanguage.getOrDefault(language, byLanguage.get(Language.EN));
    }

    /** 接口里的写法：字符串，或者按 en、zh、ja 顺序的对象（没写的语言不出现）。 */
    public Object wire() {
        if (byLanguage.isEmpty()) {
            return plain;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Language language : WIRE_ORDER) {
            String text = byLanguage.get(language);
            if (text != null) {
                out.put(language.id(), text);
            }
        }
        return out;
    }
}
