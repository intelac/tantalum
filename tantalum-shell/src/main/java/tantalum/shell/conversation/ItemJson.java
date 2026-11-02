package tantalum.shell.conversation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 条目的接口格式：和快照相同的记录字段（{@code kind} 在最前），只是会话和运行摊平成字符串。 */
public final class ItemJson {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private ItemJson() {}

    /** {@code mapper} 要能序列化 {@code Json} 和内容块，即 {@code EventCodec} 的 mapper。 */
    public static Map<String, Object> toMap(JsonMapper mapper, TranscriptItem item) {
        Map<String, Object> fields = mapper.convertValue(item, MAP);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", fields.get("kind"));
        data.put("id", item.id());
        data.put("session", item.session().value());
        if (item.run() != null) {
            data.put("run", item.run().value());
        }
        fields.keySet().removeAll(Set.of("kind", "id", "session", "run"));
        data.putAll(fields);
        return data;
    }
}
