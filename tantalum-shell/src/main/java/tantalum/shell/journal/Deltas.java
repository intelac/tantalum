package tantalum.shell.journal;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 结果行里“只写和上一份不同的字段”的通用写法，会话上下文和运行状态都用（从 {@code RecordCodec} 拆出）。 */
final class Deltas {

    private final JsonMapper mapper;

    Deltas(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** 只留和上一份不同的字段（没有的字段算 null），{@code keep} 字段总是留着，用来找到上一份。 */
    ObjectNode delta(ObjectNode now, ObjectNode before, String keep) {
        ObjectNode delta = mapper.createObjectNode();
        delta.put("delta", true);
        Set<String> names = new LinkedHashSet<>(now.propertyNames());
        names.addAll(before.propertyNames());
        for (String name : names) {
            JsonNode value = now.has(name) ? now.get(name) : mapper.nullNode();
            if (name.equals(keep) || !value.equals(before.has(name) ? before.get(name) : mapper.nullNode())) {
                delta.set(name, value);
            }
        }
        return delta;
    }

    /** {@link #delta} 的反向。 */
    ObjectNode merge(JsonNode delta, Object before, String what) {
        if (before == null) {
            throw new IllegalArgumentException(what + " change refers to an earlier " + what + " that is not in the file");
        }
        ObjectNode merged = mapper.valueToTree(before);
        for (Map.Entry<String, JsonNode> field : delta.properties()) {
            if (!field.getKey().equals("delta")) {
                if (field.getValue().isNull()) {
                    merged.remove(field.getKey());
                } else {
                    merged.set(field.getKey(), field.getValue());
                }
            }
        }
        return merged;
    }
}
