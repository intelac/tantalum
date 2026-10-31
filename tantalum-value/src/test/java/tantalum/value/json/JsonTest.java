package tantalum.value.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void valuesCompareByContent() {
        assertEquals(new Json.Null(), new Json.Null());
        assertEquals(new Json.Bool(true), new Json.Bool(true));
        assertEquals(new Json.Num(BigDecimal.TEN), new Json.Num(BigDecimal.TEN));
        assertNotEquals(new Json.Str("a"), new Json.Str("b"));
        assertEquals(new Json.Arr(List.of(new Json.Null())), new Json.Arr(List.of(new Json.Null())));
    }

    @Test
    void objectKeepsKeyOrder() {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("z", new Json.Null());
        fields.put("a", new Json.Null());
        assertEquals(List.of("z", "a"), List.copyOf(new Json.Obj(fields).fields().keySet()));
    }

    @Test
    void containersDoNotShareInput() {
        List<Json> items = new ArrayList<>(List.of(new Json.Null()));
        Json.Arr array = new Json.Arr(items);
        items.clear();
        assertEquals(1, array.items().size());
        assertThrows(UnsupportedOperationException.class, () -> array.items().clear());

        Map<String, Json> fields = new LinkedHashMap<>(Map.of("a", new Json.Null()));
        Json.Obj object = new Json.Obj(fields);
        fields.clear();
        assertEquals(1, object.fields().size());
        assertThrows(UnsupportedOperationException.class, () -> object.fields().clear());
    }

    @Test
    void nullsAreRejected() {
        assertThrows(NullPointerException.class, () -> new Json.Str(null));
        assertThrows(NullPointerException.class, () -> new Json.Num(null));
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("a", null);
        assertThrows(NullPointerException.class, () -> new Json.Obj(fields));
    }
}
