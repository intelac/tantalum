package tantalum.value.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** console-i18n 需求 1.1、5.2：浏览器标签和终端环境变量怎么选语言。 */
class LanguageTest {

    @Test
    void parsesOnlyTheThreeIds() {
        assertEquals(Optional.of(Language.JA), Language.parse("ja"));
        assertEquals(Optional.empty(), Language.parse("fr"));
        assertEquals(Optional.empty(), Language.parse("JA"));
        assertEquals(Optional.empty(), Language.parse(null));
    }

    @Test
    void tagsMatchWholeThenPrimary() {
        assertEquals(Language.JA, Language.fromTags(List.of("ja-JP")));
        assertEquals(Language.ZH, Language.fromTags(List.of("zh-TW")));
        assertEquals(Language.JA, Language.fromTags(List.of("fr-FR", "ja")));
        assertEquals(Language.EN, Language.fromTags(List.of("fr")));
        assertEquals(Language.EN, Language.fromTags(List.of()));
        assertEquals(Language.EN, Language.fromTags(List.of("EN-gb")));
    }

    static Stream<Arguments> environments() {
        return Stream.of(
                Arguments.of(Map.of("LC_ALL", "ja_JP.UTF-8"), Locale.US, Language.JA),
                Arguments.of(Map.of("LC_ALL", "", "LANG", "zh_CN.UTF-8"), Locale.US, Language.ZH),
                Arguments.of(Map.of("LC_ALL", "ja_JP.UTF-8", "LANG", "zh_CN.UTF-8"), Locale.US, Language.JA),
                Arguments.of(Map.of("LC_MESSAGES", "zh_TW.Big5@stroke"), Locale.US, Language.ZH),
                Arguments.of(Map.of("LANG", "C"), Locale.JAPAN, Language.JA),
                Arguments.of(Map.of("LANG", "POSIX"), Locale.US, Language.EN),
                Arguments.of(Map.of("LANG", "fr_FR.UTF-8"), Locale.JAPAN, Language.EN),
                Arguments.of(Map.of(), Locale.SIMPLIFIED_CHINESE, Language.ZH),
                Arguments.of(Map.of(), Locale.GERMANY, Language.EN));
    }

    @ParameterizedTest
    @MethodSource("environments")
    void environmentPicksTheFirstSetVariableThenTheSystem(Map<String, String> env, Locale system, Language expected) {
        assertEquals(expected, Language.fromEnvironment(env::get, system));
    }
}
