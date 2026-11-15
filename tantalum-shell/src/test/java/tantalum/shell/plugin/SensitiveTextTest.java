package tantalum.shell.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** memory-final-plan 第 7 节：统一的敏感信息检测。假的密钥按拼接造，免得仓库里出现像真的字符串。 */
class SensitiveTextTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
        "a valid ID number                  | 我的身份证 11010519491231002X           | national ID number",
        "an ID number with a wrong check    | 编号 110105194912310021               | -",
        "a mobile number                    | 手机 138 1234 5678                    | mobile phone number",
        "a mobile number with country code  | call +86-13912345678                 | mobile phone number",
        "an order number is not a phone     | 订单 2381234567890                    | -",
        "a date and time is not an ID       | 2026-11-16 10:00                    | -",
        "plain words                        | 以后回答用简体中文                       | -",
    })
    void builtInRules(String name, String text, String expected) {
        assertEquals(Optional.ofNullable(expected), SensitiveText.of(Set.of(), Map.of()).detect(text));
    }

    @Test
    void keysAreFoundWithoutEchoingThem() {
        String key = "AKIA" + "ABCDEFGHIJKLMNOP";
        assertEquals(Optional.of("AWS access key"), SensitiveText.of(Set.of(), Map.of()).detect("key " + key));
    }

    @Test
    void anAdministratorCanSwitchRulesOffAndAddTheirOwn() {
        SensitiveText configured = SensitiveText.of(Set.of(SensitiveText.PHONE), Map.of("employee number", "\\bEMP-\\d{6}\\b"));
        assertEquals(Optional.empty(), configured.detect("手机 13812345678"));
        assertEquals(Optional.of("employee number"), configured.detect("工号 EMP-123456"));
        assertThrows(IllegalArgumentException.class, () -> SensitiveText.of(Set.of(), Map.of("broken", "(")));
    }
}
