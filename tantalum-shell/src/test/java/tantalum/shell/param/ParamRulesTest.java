package tantalum.shell.param;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.param.ParamRules.InvalidParam;
import tantalum.shell.plugin.Param;
import tantalum.value.json.Json;

class ParamRulesTest {

    private static Json num(String text) {
        return new Json.Num(new BigDecimal(text));
    }

    private static Json strings(String... items) {
        List<Json> list = new ArrayList<>();
        for (String item : items) {
            list.add(new Json.Str(item));
        }
        return new Json.Arr(list);
    }

    private static String reason(Param param, Json value) {
        return assertThrows(InvalidParam.class, () -> ParamRules.check(param, value)).reason();
    }

    @Test
    void acceptedValuesAreNormalized() {
        assertEquals(new Json.Str("hello"), ParamRules.check(Param.text("t", ""), new Json.Str("  hello \n")));
        assertEquals(num("5"), ParamRules.check(Param.number("n", 1).range(1, 10), num("5.0")));
        assertEquals(new Json.Bool(false), ParamRules.check(Param.toggle("on", true), new Json.Bool(false)));
        assertEquals(strings("a", "b"), ParamRules.check(Param.list("l", List.of()), strings(" a ", "", "  ", "b")));
        // 密码原样保留，首尾空白也是密码的一部分
        assertEquals(new Json.Str(" s3cret "), ParamRules.check(Param.secret("token"), new Json.Str(" s3cret ")));
    }

    @Test
    void wrongTypesAreRejected() {
        assertEquals("type", reason(Param.text("t", ""), num("1")));
        assertEquals("type", reason(Param.number("n", 1), new Json.Str("1")));
        assertEquals("type", reason(Param.number("n", 1), num("1.5")));
        assertEquals("type", reason(Param.toggle("on", true), new Json.Str("true")));
        assertEquals("type", reason(Param.list("l", List.of()), new Json.Str("a")));
        assertEquals("type", reason(Param.list("l", List.of()), new Json.Arr(List.of(num("1")))));
        assertEquals("type", reason(Param.secret("token"), new Json.Str("")));
        assertEquals("type", reason(Param.text("t", ""), new Json.Null()));
    }

    @Test
    void limitsAreEnforced() {
        Param ranged = Param.number("n", 5).range(1, 10);
        assertEquals("range", reason(ranged, num("0")));
        assertEquals("range", reason(ranged, num("11")));
        assertEquals("range", reason(ranged, num("1e30")));
        assertEquals(num("1"), ParamRules.check(ranged, num("1")));
        assertEquals(num("10"), ParamRules.check(ranged, num("10")));
        assertEquals("tooLong", reason(Param.text("t", ""), new Json.Str("x".repeat(ParamRules.TEXT_MAX + 1))));
        assertEquals("tooLong", reason(Param.list("l", List.of()), strings("x".repeat(ParamRules.ITEM_MAX + 1))));
        assertEquals("tooLong", reason(Param.secret("token"), new Json.Str("x".repeat(ParamRules.SECRET_MAX + 1))));
        String[] many = new String[ParamRules.LIST_MAX + 1];
        java.util.Arrays.setAll(many, i -> "item" + i);
        assertEquals("tooMany", reason(Param.list("l", List.of()), strings(many)));
    }

    /** 报错信息会进日志和响应：不能带值，密码更不能。 */
    @Test
    void theMessageNeverCarriesTheValue() {
        String secret = "s".repeat(ParamRules.SECRET_MAX + 1);
        InvalidParam failure = assertThrows(InvalidParam.class, () -> ParamRules.check(Param.secret("token"), new Json.Str(secret)));
        assertFalse(failure.getMessage().contains("sss"));
        assertEquals("token", failure.key());
    }
}
