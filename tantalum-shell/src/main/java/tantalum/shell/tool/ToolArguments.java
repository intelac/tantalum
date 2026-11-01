package tantalum.shell.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.value.json.Json;

/** 读取工具参数，生成简单的字符串参数 schema。 */
public final class ToolArguments {

    private ToolArguments() {}

    public static String string(Json arguments, String name) {
        if (arguments instanceof Json.Obj object && object.fields().get(name) instanceof Json.Str value) {
            return value.value();
        }
        return null;
    }

    /** 全部参数都是字符串；{@code required} 写进 schema 的 {@code required} 数组，模型才知道哪些不能省。 */
    public static Json schema(List<String> required, List<String> optional) {
        Map<String, Json> properties = new LinkedHashMap<>();
        for (String name : required) {
            properties.put(name, new Json.Obj(Map.of("type", new Json.Str("string"))));
        }
        for (String name : optional) {
            properties.put(name, new Json.Obj(Map.of("type", new Json.Str("string"))));
        }
        Map<String, Json> schema = new LinkedHashMap<>();
        schema.put("type", new Json.Str("object"));
        schema.put("properties", new Json.Obj(properties));
        List<Json> names = new ArrayList<>();
        required.forEach(name -> names.add(new Json.Str(name)));
        schema.put("required", new Json.Arr(names));
        return new Json.Obj(schema);
    }
}
