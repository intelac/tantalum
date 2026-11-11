package tantalum.value.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.value.json.Json;

class ParamsTest {

    private static Params of(Object... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put((String) pairs[i], (Json) pairs[i + 1]);
        }
        return new Params(new Json.Obj(fields));
    }

    @Test
    void readsEachType() {
        Params params = of("name", new Json.Str("a"), "n", new Json.Num(BigDecimal.valueOf(7)), "on", new Json.Bool(true),
                "items", new Json.Arr(List.of(new Json.Str("x"), new Json.Str("y"))));
        assertEquals("a", params.text("name", "z"));
        assertEquals(7, params.number("n", 0));
        assertTrue(params.flag("on", false));
        assertEquals(List.of("x", "y"), params.list("items", List.of()));
        assertTrue(params.has("n"));
    }

    /** 旧会话没记参数：钩子拿到 NONE，用自己构造时的值。 */
    @Test
    void aMissingKeyGivesTheFallback() {
        assertEquals("z", Params.NONE.text("name", "z"));
        assertEquals(9, Params.NONE.number("n", 9));
        assertTrue(Params.NONE.flag("on", true));
        assertEquals(List.of("k"), Params.NONE.list("items", List.of("k")));
        assertFalse(Params.NONE.has("n"));
    }

    @Test
    void aWrongTypeGivesTheFallback() {
        Params params = of("name", new Json.Bool(true), "n", new Json.Str("7"), "half", new Json.Num(new BigDecimal("1.5")),
                "items", new Json.Arr(List.of(new Json.Str("x"), new Json.Bool(false))));
        assertEquals("z", params.text("name", "z"));
        assertEquals(9, params.number("n", 9));
        assertEquals(9, params.number("half", 9));
        assertEquals(List.of("k"), params.list("items", List.of("k")));
    }
}
