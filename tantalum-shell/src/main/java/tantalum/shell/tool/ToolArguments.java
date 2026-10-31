package tantalum.shell.tool;

import tantalum.value.json.Json;

/** 读取工具参数。 */
final class ToolArguments {

    private ToolArguments() {}

    static String string(Json arguments, String name) {
        if (arguments instanceof Json.Obj object && object.fields().get(name) instanceof Json.Str value) {
            return value.value();
        }
        return null;
    }

    static Json schema(String... required) {
        java.util.Map<String, Json> properties = new java.util.LinkedHashMap<>();
        for (String name : required) {
            properties.put(name, new Json.Obj(java.util.Map.of("type", new Json.Str("string"))));
        }
        java.util.Map<String, Json> schema = new java.util.LinkedHashMap<>();
        schema.put("type", new Json.Str("object"));
        schema.put("properties", new Json.Obj(properties));
        return new Json.Obj(schema);
    }
}
