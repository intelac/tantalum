package tantalum.shell.journal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tantalum.value.event.ContextAssembled;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 组装上下文（{@link ContextAssembled}）的载荷：这个会话配上了什么，只在变化时记（one-line-journal J6）。
 * 会话第一次组装、压缩之后、写入方不知道前文时写全文，和别的事件一样；其余时候写 {@code "diff": true}，
 * 只带和上一次相比变了的字段，{@code command} 和 {@code compaction} 总是写。工具按名字比较：工具名单变了写 {@code toolNames}
 * （新的顺序），新增的和定义变了的写进 {@code toolSpecs}，其余的从上一次取。读的时候套回上一次的全文，得到完整的事件。
 */
final class MatchedCodec {

    private static final String DIFF = "diff";
    private static final String TOOLS = "tools";
    private static final String TOOL_NAMES = "toolNames";
    private static final String TOOL_SPECS = "toolSpecs";
    /** 总是写的字段。 */
    private static final List<String> ALWAYS = List.of("command", "compaction");

    private final JsonMapper mapper;

    MatchedCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** 编码一次组装，并把它的全文记进 {@code memo}。 */
    String encode(ContextAssembled event, RecordMemo memo) {
        ObjectNode full = mapper.valueToTree(event);
        JsonNode last = memo.matched();
        memo.matched(full);
        if (last == null) {
            return mapper.writeValueAsString(full);
        }
        ObjectNode diff = mapper.createObjectNode();
        diff.put(DIFF, true);
        for (Map.Entry<String, JsonNode> field : full.properties()) {
            String name = field.getKey();
            if (name.equals(TOOLS)) {
                tools(field.getValue(), last.path(TOOLS), diff);
            } else if (ALWAYS.contains(name) || !same(field.getValue(), last.get(name))) {
                diff.set(name, field.getValue());
            }
        }
        // 上一次有、这一次没有的字段（比如名单变成全部开着）：写 null，读的时候删掉
        for (Map.Entry<String, JsonNode> field : ((ObjectNode) last).properties()) {
            if (!full.has(field.getKey()) && !ALWAYS.contains(field.getKey())) {
                diff.putNull(field.getKey());
            }
        }
        return mapper.writeValueAsString(diff);
    }

    /** 解一次组装的载荷，返回完整的载荷树，并记进 {@code memo}。只有变化、却不知道上一次时抛 {@link IllegalArgumentException}。 */
    JsonNode decode(JsonNode payload, RecordMemo memo) {
        if (!payload.path(DIFF).asBoolean(false)) {
            memo.matched(payload);
            return payload;
        }
        JsonNode last = memo.matched();
        if (last == null) {
            throw new IllegalArgumentException("context changes without an earlier full context");
        }
        ObjectNode full = (ObjectNode) last.deepCopy();
        for (String name : ALWAYS) {
            full.remove(name);
        }
        for (Map.Entry<String, JsonNode> field : payload.properties()) {
            String name = field.getKey();
            if (name.equals(DIFF) || name.equals(TOOL_SPECS)) {
                continue;
            }
            if (name.equals(TOOL_NAMES)) {
                full.set(TOOLS, tools(field.getValue(), payload.path(TOOL_SPECS), last.path(TOOLS)));
            } else if (field.getValue().isNull() && !ALWAYS.contains(name)) {
                full.remove(name);
            } else {
                full.set(name, field.getValue());
            }
        }
        memo.matched(full);
        return full;
    }

    /** 工具名单或某个工具的定义变了才写：新的名单和变了的定义。 */
    private void tools(JsonNode now, JsonNode before, ObjectNode diff) {
        if (same(now, before)) {
            return;
        }
        Map<String, JsonNode> earlier = byName(before);
        ArrayNode names = diff.putArray(TOOL_NAMES);
        ArrayNode specs = mapper.createArrayNode();
        for (JsonNode tool : now) {
            String name = tool.path("name").asString();
            names.add(name);
            if (!same(tool, earlier.get(name))) {
                specs.add(tool);
            }
        }
        if (!specs.isEmpty()) {
            diff.set(TOOL_SPECS, specs);
        }
    }

    /**
     * 按写出来的文字比较：冷载入后上一次的全文是从文件解析的（数字是 {@code BigDecimal} 或整数节点），这一次是从对象转的，
     * 同一个数在两边的节点类型可能不同，{@code equals} 会把没变的当成变了。
     */
    private boolean same(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.equals(b) || mapper.writeValueAsString(a).equals(mapper.writeValueAsString(b));
    }

    private ArrayNode tools(JsonNode names, JsonNode specs, JsonNode before) {
        Map<String, JsonNode> known = byName(before);
        known.putAll(byName(specs));
        ArrayNode tools = mapper.createArrayNode();
        for (JsonNode name : names) {
            JsonNode tool = known.get(name.asString());
            if (tool == null) {
                throw new IllegalArgumentException("context changes name tool " + name.asString() + " without its definition");
            }
            tools.add(tool);
        }
        return tools;
    }

    private static Map<String, JsonNode> byName(JsonNode tools) {
        Map<String, JsonNode> byName = new HashMap<>();
        for (JsonNode tool : tools) {
            byName.put(tool.path("name").asString(), tool);
        }
        return byName;
    }
}
