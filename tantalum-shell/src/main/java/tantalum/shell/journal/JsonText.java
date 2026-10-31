package tantalum.shell.journal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.value.json.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

/** JSON 文本与 {@link Json} 互转。保留键的顺序和数字原文。 */
public final class JsonText {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JsonText() {}

    /** 解析失败时返回 null，由调用方决定怎么处理。 */
    public static Json parseOrNull(String text) {
        try (JsonParser in = MAPPER.createParser(text)) {
            in.nextToken();
            Json value = read(in);
            return in.nextToken() == null ? value : null;
        } catch (JacksonException | IllegalArgumentException | NullPointerException failure) {
            return null;
        }
    }

    /** 按 token 读取，数字取原文，避免经过树节点时丢失精度。 */
    static Json read(JsonParser in) {
        return switch (in.currentToken()) {
            case VALUE_NULL -> new Json.Null();
            case VALUE_TRUE -> new Json.Bool(true);
            case VALUE_FALSE -> new Json.Bool(false);
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> new Json.Num(new BigDecimal(in.getString()));
            case VALUE_STRING -> new Json.Str(in.getString());
            case START_ARRAY -> {
                List<Json> items = new ArrayList<>();
                while (in.nextToken() != JsonToken.END_ARRAY) {
                    items.add(read(in));
                }
                yield new Json.Arr(items);
            }
            case START_OBJECT -> {
                Map<String, Json> fields = new LinkedHashMap<>();
                while (in.nextToken() != JsonToken.END_OBJECT) {
                    String name = in.currentName();
                    in.nextToken();
                    fields.put(name, read(in));
                }
                yield new Json.Obj(fields);
            }
            default -> throw new IllegalArgumentException("unexpected token " + in.currentToken());
        };
    }

    /** 转成 Jackson 和 SDK 都接受的普通 Java 对象。 */
    public static Object toPlain(Json value) {
        return switch (value) {
            case Json.Null ignored -> null;
            case Json.Bool bool -> bool.value();
            case Json.Num num -> num.value();
            case Json.Str str -> str.value();
            case Json.Arr arr -> {
                List<Object> items = new ArrayList<>();
                arr.items().forEach(item -> items.add(toPlain(item)));
                yield items;
            }
            case Json.Obj obj -> {
                Map<String, Object> fields = new LinkedHashMap<>();
                obj.fields().forEach((key, item) -> fields.put(key, toPlain(item)));
                yield fields;
            }
        };
    }
}
