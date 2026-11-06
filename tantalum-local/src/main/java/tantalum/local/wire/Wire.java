package tantalum.local.wire;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.value.json.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * 消息的编码和解码（local-workspace 设计“tantalum.local.wire”）。不认识的字段忽略，以后加字段不用升版本；不认识的 {@code type}
 * 解码失败，收到的一方关掉连接。不兼容的改动把 {@link #PROTOCOL} 加 1，服务器不认识的版本用 {@link #CLOSE_PROTOCOL} 拒绝。
 */
public final class Wire {

    /**
     * 协议版本，写在 {@link Hello#protocol()} 里。2（2026-11-06）：{@code hello.roots[].git} 换成 {@code hello.git}，Git 工具不再要求
     * 主目录是仓库。意思变了，所以加 1：旧的连接程序连上来被拒绝，提示下载新的，不会悄悄少了 Git 工具。
     */
    public static final int PROTOCOL = 2;

    /** 关闭码：协议版本不对。连接程序收到这四个就退出，不重连；其他关闭都重连。 */
    public static final int CLOSE_PROTOCOL = 4400;
    /** 关闭码：令牌无效。 */
    public static final int CLOSE_BAD_TOKEN = 4401;
    /** 关闭码：令牌已到期或已撤销。 */
    public static final int CLOSE_EXPIRED = 4403;
    /** 关闭码：被同一用户的另一条连接替换（L10）。 */
    public static final int CLOSE_REPLACED = 4409;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .addModule(new SimpleModule("tantalum-json")
                    .addSerializer(Json.class, new JsonValueSerializer())
                    .addDeserializer(Json.class, new JsonValueDeserializer()))
            .build();
    private static final ObjectWriter WRITER = MAPPER.writerFor(Message.class);

    private Wire() {}

    public static String encode(Message message) {
        return WRITER.writeValueAsString(message);
    }

    /** 解码一帧。不是 JSON、没有或不认识 {@code type}、缺了必需的字段时抛 {@link IllegalArgumentException}。 */
    public static Message decode(String text) {
        try {
            return MAPPER.readValue(text, Message.class);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("bad message: " + failure.getOriginalMessage(), failure);
        }
    }

    /** {@link Json} 按原样写：保留键的顺序和数字原文。 */
    private static final class JsonValueSerializer extends ValueSerializer<Json> {

        @Override
        public void serialize(Json value, JsonGenerator out, SerializationContext context) {
            switch (value) {
                case Json.Null ignored -> out.writeNull();
                case Json.Bool bool -> out.writeBoolean(bool.value());
                case Json.Num num -> out.writeNumber(num.value());
                case Json.Str str -> out.writeString(str.value());
                case Json.Arr arr -> {
                    out.writeStartArray();
                    for (Json item : arr.items()) {
                        serialize(item, out, context);
                    }
                    out.writeEndArray();
                }
                case Json.Obj obj -> {
                    out.writeStartObject();
                    for (Map.Entry<String, Json> field : obj.fields().entrySet()) {
                        out.writeName(field.getKey());
                        serialize(field.getValue(), out, context);
                    }
                    out.writeEndObject();
                }
            }
        }
    }

    /** 按 token 读，数字取原文，不经过 double 丢精度。 */
    private static final class JsonValueDeserializer extends ValueDeserializer<Json> {

        @Override
        public Json deserialize(JsonParser in, DeserializationContext context) {
            return read(in);
        }

        private static Json read(JsonParser in) {
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
                case START_OBJECT, PROPERTY_NAME -> {
                    Map<String, Json> fields = new LinkedHashMap<>();
                    JsonToken token = in.currentToken() == JsonToken.START_OBJECT ? in.nextToken() : in.currentToken();
                    for (; token != JsonToken.END_OBJECT; token = in.nextToken()) {
                        String name = in.currentName();
                        in.nextToken();
                        fields.put(name, read(in));
                    }
                    yield new Json.Obj(fields);
                }
                default -> throw new IllegalArgumentException("unexpected token " + in.currentToken());
            };
        }
    }
}
