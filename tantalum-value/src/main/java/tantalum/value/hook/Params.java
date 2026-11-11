package tantalum.value.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tantalum.value.json.Json;

/**
 * 一个插件的一包参数值。键不存在、或类型不对时返回调用方给的值：没记过参数的旧会话里，钩子就用自己构造时的值。
 * 里面没有密码。
 */
public record Params(Json.Obj values) {

    public static final Params NONE = new Params(new Json.Obj(Map.of()));

    public Params {
        Objects.requireNonNull(values, "values");
    }

    public boolean has(String key) {
        return values.fields().containsKey(key);
    }

    public String text(String key, String fallback) {
        return values.fields().get(key) instanceof Json.Str text ? text.value() : fallback;
    }

    /** 不是整数（带小数、超出 long）也算类型不对。 */
    public long number(String key, long fallback) {
        if (values.fields().get(key) instanceof Json.Num number) {
            try {
                return number.value().longValueExact();
            } catch (ArithmeticException notWhole) {
                return fallback;
            }
        }
        return fallback;
    }

    public boolean flag(String key, boolean fallback) {
        return values.fields().get(key) instanceof Json.Bool flag ? flag.value() : fallback;
    }

    /** 有一项不是字符串，整个算类型不对。 */
    public List<String> list(String key, List<String> fallback) {
        if (!(values.fields().get(key) instanceof Json.Arr array)) {
            return fallback;
        }
        List<String> items = new ArrayList<>();
        for (Json item : array.items()) {
            if (!(item instanceof Json.Str text)) {
                return fallback;
            }
            items.add(text.value());
        }
        return List.copyOf(items);
    }
}
