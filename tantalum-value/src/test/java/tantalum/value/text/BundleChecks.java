package tantalum.value.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 资源文件齐不齐（console-i18n I14）：每个键三份都有、没有多的键、三份的占位符相同、英文里没有中日文字符。
 * 连接程序和命令行的测试都调它（经 tantalum-value 的测试包）。
 */
public final class BundleChecks {

    private static final Pattern CJK = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff\\uff00-\\uffef]");

    private BundleChecks() {}

    public static void assertComplete(String baseName, Class<? extends Enum<?>> keys) {
        Set<String> expected = new TreeSet<>();
        for (Enum<?> constant : keys.getEnumConstants()) {
            expected.add(Bundle.key(constant));
        }
        Map<String, String> english = Bundle.ownTemplates(baseName, Language.EN);
        List<String> problems = new ArrayList<>();
        for (Language language : Language.values()) {
            Map<String, String> own = Bundle.ownTemplates(baseName, language);
            for (String key : expected) {
                String template = own.get(key);
                if (template == null || template.isBlank()) {
                    problems.add(language.id() + ": missing " + key);
                } else if (english.containsKey(key) && !Bundle.placeholders(template).equals(Bundle.placeholders(english.get(key)))) {
                    problems.add(language.id() + ": placeholders of " + key + " differ from English: " + template);
                }
            }
            for (String key : own.keySet()) {
                if (!expected.contains(key)) {
                    problems.add(language.id() + ": extra key " + key);
                }
            }
        }
        english.forEach((key, template) -> {
            if (CJK.matcher(template).find()) {
                problems.add("en: Chinese or Japanese characters in " + key);
            }
        });
        assertTrue(expected.size() > 0, "no keys in " + keys);
        assertEquals(List.of(), problems, baseName);
    }
}
