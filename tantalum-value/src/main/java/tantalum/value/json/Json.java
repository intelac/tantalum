package tantalum.value.json;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 不依赖 JSON 库的不可变 JSON 值。对象保留键的顺序，这样还原 prompt 时内容不变。 */
public sealed interface Json {

    record Null() implements Json {}

    record Bool(boolean value) implements Json {}

    record Num(BigDecimal value) implements Json {
        public Num {
            Objects.requireNonNull(value, "value");
        }
    }

    record Str(String value) implements Json {
        public Str {
            Objects.requireNonNull(value, "value");
        }
    }

    record Arr(List<Json> items) implements Json {
        public Arr {
            items = List.copyOf(items);
        }
    }

    record Obj(Map<String, Json> fields) implements Json {
        public Obj {
            Map<String, Json> copy = new LinkedHashMap<>();
            fields.forEach((key, value) -> copy.put(
                    Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value")));
            fields = Collections.unmodifiableMap(copy);
        }
    }
}
