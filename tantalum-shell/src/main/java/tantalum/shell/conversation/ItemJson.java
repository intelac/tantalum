package tantalum.shell.conversation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 条目的接口格式：{@code kind} 标明类型，会话和运行是字符串，其余字段照记录输出，内容块与 journal 一致。 */
public final class ItemJson {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private ItemJson() {}

    /** {@code mapper} 要能序列化 {@code Json} 和内容块，即 {@code EventCodec} 的 mapper。 */
    public static Map<String, Object> toMap(JsonMapper mapper, TranscriptItem item) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", kind(item));
        data.put("id", item.id());
        data.put("session", item.session().value());
        if (item.run() != null) {
            data.put("run", item.run().value());
        }
        Map<String, Object> fields = mapper.convertValue(item, MAP);
        fields.keySet().removeAll(Set.of("id", "session", "run"));
        data.putAll(fields);
        return data;
    }

    public static String kind(TranscriptItem item) {
        return switch (item) {
            case TranscriptItem.UserMessage ignored -> "user";
            case TranscriptItem.Text ignored -> "text";
            case TranscriptItem.Thinking ignored -> "thinking";
            case TranscriptItem.ToolStep ignored -> "tool";
            case TranscriptItem.RunStatus ignored -> "run";
        };
    }
}
