package tantalum.shell.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.json.Json;
import tantalum.value.text.LocalText;

class ParamTest {

    @Test
    void factoriesCarryTheDefault() {
        assertEquals(new Json.Str("a"), Param.text("name", "a").byDefault());
        assertEquals(new Json.Num(BigDecimal.valueOf(5)), Param.number("n", 5).byDefault());
        assertEquals(new Json.Bool(true), Param.toggle("on", true).byDefault());
        assertEquals(new Json.Arr(List.of(new Json.Str("x"), new Json.Str("y"))), Param.list("items", List.of("x", "y")).byDefault());
        assertNull(Param.secret("token").byDefault());
        assertTrue(Param.secret("token").secret());
        assertEquals(Param.Owner.USER, Param.number("n", 5).owner());
    }

    @Test
    void modifiersReturnACopy() {
        Param plain = Param.number("tokens", 100);
        Param full = plain.label(LocalText.of("Tokens")).hint(LocalText.of("per day")).range(1, 1000).adminOnly();
        assertEquals(1L, full.min());
        assertEquals(1000L, full.max());
        assertEquals(Param.Owner.ADMIN, full.owner());
        assertEquals(LocalText.of("Tokens"), full.label());
        assertNull(plain.min());
        assertEquals(Param.Owner.USER, plain.owner());
        assertEquals(1L, plain.atLeast(1).min());
        assertNull(plain.atLeast(1).max());
    }

    @Test
    void badDeclarationsFailAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> Param.text("has space", "a"));
        assertThrows(IllegalArgumentException.class, () -> Param.text("", "a"));
        assertThrows(IllegalArgumentException.class, () -> Param.text("k".repeat(65), "a"));
        // 默认值不在范围里
        assertThrows(IllegalArgumentException.class, () -> Param.number("n", 0).range(1, 10));
        assertThrows(IllegalArgumentException.class, () -> Param.number("n", 5).range(10, 1));
        // 范围只对数字有意义
        assertThrows(IllegalArgumentException.class, () -> Param.text("t", "a").range(1, 10));
        // 密码不能由管理员替人填
        assertThrows(IllegalArgumentException.class, () -> Param.secret("token").adminOnly());
    }
}
