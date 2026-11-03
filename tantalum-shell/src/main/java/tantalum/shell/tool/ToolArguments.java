package tantalum.shell.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.value.json.Json;

/** 读取工具参数，生成参数 schema。 */
public final class ToolArguments {

    /** 一个参数：{@code type} 是 JSON Schema 的类型名（string、integer、boolean）。 */
    public record Param(String name, String type, String description, boolean required) {

        public static Param required(String name, String type, String description) {
            return new Param(name, type, description, true);
        }

        public static Param optional(String name, String type, String description) {
            return new Param(name, type, description, false);
        }
    }

    private ToolArguments() {}

    /** 整数参数；没给时返回 null。模型有时把数字写成字符串，也认。不是整数时抛 {@link IllegalArgumentException}。 */
    public static Integer integer(Json arguments, String name) {
        Json value = arguments instanceof Json.Obj object ? object.fields().get(name) : null;
        try {
            return switch (value) {
                case null -> null;
                case Json.Null ignored -> null;
                case Json.Num number -> number.value().intValueExact();
                case Json.Str text when !text.value().isBlank() -> Integer.valueOf(text.value().strip());
                default -> throw new IllegalArgumentException(name + " must be an integer");
            };
        } catch (ArithmeticException | NumberFormatException notAnInteger) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    /** 布尔参数；没给时返回 {@code fallback}。也认字符串 "true"、"false"。 */
    public static boolean bool(Json arguments, String name, boolean fallback) {
        Json value = arguments instanceof Json.Obj object ? object.fields().get(name) : null;
        return switch (value) {
            case null -> fallback;
            case Json.Null ignored -> fallback;
            case Json.Bool flag -> flag.value();
            case Json.Str text when text.value().equalsIgnoreCase("true") -> true;
            case Json.Str text when text.value().equalsIgnoreCase("false") -> false;
            default -> throw new IllegalArgumentException(name + " must be true or false");
        };
    }

    public static Json schema(List<Param> params) {
        Map<String, Json> properties = new LinkedHashMap<>();
        List<Json> required = new ArrayList<>();
        for (Param param : params) {
            Map<String, Json> property = new LinkedHashMap<>();
            property.put("type", new Json.Str(param.type()));
            property.put("description", new Json.Str(param.description()));
            properties.put(param.name(), new Json.Obj(property));
            if (param.required()) {
                required.add(new Json.Str(param.name()));
            }
        }
        Map<String, Json> schema = new LinkedHashMap<>();
        schema.put("type", new Json.Str("object"));
        schema.put("properties", new Json.Obj(properties));
        schema.put("required", new Json.Arr(required));
        return new Json.Obj(schema);
    }

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
