package tantalum.shell.trace;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 记录的接口格式：{@code kind} 在最前，然后是摊平的共有字段（运行摊平成字符串），再是各类记录自己的字段。同 {@code ItemJson}。 */
public final class TraceJson {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private TraceJson() {}

    /** {@code mapper} 要能序列化 {@code Json}，即 {@code EventCodec} 的 mapper（空值不写）。 */
    public static Map<String, Object> toMap(JsonMapper mapper, TraceRecord record) {
        Map<String, Object> fields = mapper.convertValue(record, MAP);
        @SuppressWarnings("unchecked")
        Map<String, Object> head = (Map<String, Object>) fields.remove("head");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", fields.remove("kind"));
        data.putAll(head);
        data.put("run", record.head().run().value());
        data.putAll(fields);
        return data;
    }
}
