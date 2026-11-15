package tantalum.shell.plugin;

import tantalum.shell.journal.EventCodec;
import tantalum.value.json.Json;

/** 插件用的 JSON 文字读写（插件不直接依赖 JSON 库）：和 journal 用同一套规则，对象保留键的顺序。 */
public final class JsonText {

    private static final EventCodec CODEC = EventCodec.withBuiltins();

    private JsonText() {}

    /** 一行 JSON，不换行。 */
    public static String write(Json value) {
        return CODEC.mapper().writeValueAsString(value);
    }

    /** 解析不了时抛 {@link IllegalArgumentException}。 */
    public static Json read(String text) {
        try {
            return CODEC.mapper().readValue(text, Json.class);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("not valid JSON: " + invalid.getMessage(), invalid);
        }
    }
}
