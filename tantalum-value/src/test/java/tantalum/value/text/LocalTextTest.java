package tantalum.value.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** console-i18n I10、需求 4.5：插件作者的文字，一个字符串或者三种语言。 */
class LocalTextTest {

    @Test
    void plainTextIsAStringInEveryLanguage() {
        LocalText text = LocalText.of("每天的上限");
        assertEquals("每天的上限", text.wire());
        assertEquals("每天的上限", text.in(Language.EN));
        assertFalse(text.isEmpty());
        assertTrue(LocalText.of("").isEmpty());
        assertEquals("", LocalText.empty().wire());
    }

    @Test
    void languagesAreAnObjectWithEnglishFirst() {
        LocalText text = LocalText.en("Daily limit").ja("1 日の上限").zh("每天的上限");
        Object wire = text.wire();
        assertEquals(Map.of("en", "Daily limit", "zh", "每天的上限", "ja", "1 日の上限"), wire);
        assertEquals(List.of("en", "zh", "ja"), List.copyOf(((LinkedHashMap<?, ?>) wire).keySet()));
        assertEquals("1 日の上限", text.in(Language.JA));
        assertEquals(text, LocalText.of("Daily limit", "每天的上限", "1 日の上限"));
    }

    @Test
    void missingLanguageFallsBackToEnglish() {
        LocalText text = LocalText.en("Approve").zh("批准");
        assertEquals("Approve", text.in(Language.JA));
        assertEquals(Map.of("en", "Approve", "zh", "批准"), text.wire());
    }

    @Test
    void englishIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> new LocalText("", Map.of(Language.ZH, "批准")));
        assertThrows(IllegalStateException.class, () -> LocalText.of("x").zh("批准"));
    }
}
