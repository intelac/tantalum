package tantalum.value.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** console-i18n I14、需求 5.5：资源文件的读取、占位符和回退。 */
class BundleTest {

    private static final String SAMPLE = "tantalum.value.text.Sample";

    enum SampleKey { GREETING, ONLY_ENGLISH, QUOTE }

    enum Incomplete { GREETING, ONLY_ENGLISH, QUOTE, NOT_WRITTEN }

    @Test
    void replacesNamedPlaceholdersAndKeepsApostrophes() {
        Bundle english = Bundle.load(SAMPLE, Language.EN);
        assertEquals("Hello, Yi!", english.text(SampleKey.GREETING, Map.of("name", "Yi")));
        assertEquals("It's 3 files", english.text(SampleKey.QUOTE, Map.of("count", 3)));
        assertEquals("你好，Yi！", Bundle.load(SAMPLE, Language.ZH).text("greeting", Map.of("name", "Yi")));
    }

    @Test
    void missingParameterStaysVisibleAndMissingKeyShowsItsName() {
        Bundle english = Bundle.load(SAMPLE, Language.EN);
        assertEquals("Hello, {name}!", english.text(SampleKey.GREETING));
        assertEquals("no.such.key", english.text("no.such.key"));
    }

    @Test
    void aKeyMissingInJapaneseUsesEnglish() {
        assertEquals("Only in English", Bundle.load(SAMPLE, Language.JA).text(SampleKey.ONLY_ENGLISH));
    }

    /** 回退被关掉的回归：JVM 默认语言是日文时，要英文就拿到英文，不拿 Sample_ja。 */
    @Test
    void englishIsEnglishEvenWhenTheJvmIsJapanese() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.JAPAN);
            assertEquals("Hello, Yi!", Bundle.load(SAMPLE, Language.EN).text("greeting", Map.of("name", "Yi")));
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            assertEquals("こんにちは、Yiさん", Bundle.load(SAMPLE, Language.JA).text("greeting", Map.of("name", "Yi")));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void placeholdersInOrder() {
        assertEquals(Set.of("a", "b"), Bundle.placeholders("{a} and {b} and {a}"));
    }

    @Test
    void checksCatchMissingAndExtraKeys() {
        // Sample 故意让 only.english 只有英文：BundleChecks 要报出来
        AssertionError error = assertThrows(AssertionError.class, () -> BundleChecks.assertComplete(SAMPLE, SampleKey.class));
        assertEquals(true, error.getMessage().contains("zh: missing only.english"), error.getMessage());
        AssertionError extra = assertThrows(AssertionError.class, () -> BundleChecks.assertComplete(SAMPLE, Incomplete.class));
        assertEquals(true, extra.getMessage().contains("en: missing not.written"), extra.getMessage());
    }
}
