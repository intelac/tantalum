package tantalum.shell.journal;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.value.state.RunState;
import tantalum.value.state.ToolRound;
import tantalum.value.state.ToolSlot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 结果行里的运行（从 {@code RecordCodec} 拆出）。代码里 {@code RunState} 分成几块（流程、恢复、模型），账里是平的一层：
 * 每一步通常只变其中一两个字段，平着写才能只写变了的那几个。块里的字段名各不相同，也不和 {@code ref}、{@code cause} 重名。
 * <p>工具轮每完成一个调用就变一次，其中只有一格变了：两边都有工具轮时不写整个 {@code tools}，写 {@code toolSlots}（变了的格，按下标）
 * 和 {@code toolCount}、{@code toolsChecked}。这些名字不是 {@code RunState} 的字段，不会混淆。
 */
final class RunDelta {

    private final JsonMapper mapper;
    private final Deltas deltas;

    RunDelta(JsonMapper mapper, Deltas deltas) {
        this.mapper = mapper;
        this.deltas = deltas;
    }

    /** 代码里分块的那几个字段，账里摊平到运行这一层；每块有哪些字段照记录的定义。 */
    private static final Map<String, List<String>> GROUPS = groups();

    private static Map<String, List<String>> groups() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("flow", fields(RunState.Flow.class));
        groups.put("recovery", fields(RunState.Recovery.class));
        groups.put("models", fields(RunState.Models.class));
        return groups;
    }

    private static List<String> fields(Class<? extends Record> group) {
        return Arrays.stream(group.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /** 整个运行（文件里第一次出现时）。 */
    ObjectNode encode(RunState run) {
        ObjectNode tree = mapper.valueToTree(run);
        for (String group : GROUPS.keySet()) {
            tree.setAll((ObjectNode) tree.remove(group));
        }
        return tree;
    }

    /** {@link #encode(RunState)} 的反向：把摊平的字段收回各自的块里。哪一块都不认识的字段留在运行这一层，解的时候照“不认识的字段”报错。 */
    RunState decode(ObjectNode flat) {
        ObjectNode tree = flat.deepCopy();
        GROUPS.forEach((group, names) -> {
            ObjectNode fields = mapper.createObjectNode();
            for (String name : names) {
                JsonNode value = tree.remove(name);
                if (value != null) {
                    fields.set(name, value);
                }
            }
            tree.set(group, fields);
        });
        return mapper.treeToValue(tree, RunState.class);
    }

    ObjectNode encode(RunState now, RunState before) {
        ObjectNode delta = deltas.delta(encode(now), encode(before), "ref");
        if (delta.has("tools") && now.tools() != null && before.tools() != null
                && now.tools().slots().size() >= before.tools().slots().size()) {
            delta.remove("tools");
            ObjectNode slots = delta.putObject("toolSlots");
            for (int i = 0; i < now.tools().slots().size(); i++) {
                if (i >= before.tools().slots().size() || !now.tools().slots().get(i).equals(before.tools().slots().get(i))) {
                    slots.set(Integer.toString(i), mapper.valueToTree(now.tools().slots().get(i)));
                }
            }
            delta.put("toolCount", now.tools().slots().size());
            if (now.tools().checked() != before.tools().checked()) {
                delta.put("toolsChecked", now.tools().checked());
            }
        }
        return delta;
    }

    RunState merge(ObjectNode delta, RunState before) {
        JsonNode slots = delta.remove("toolSlots");
        JsonNode count = delta.remove("toolCount");
        JsonNode checked = delta.remove("toolsChecked");
        if (before == null) {
            throw new IllegalArgumentException("run change refers to an earlier run that is not in the file");
        }
        RunState merged = decode(deltas.merge(delta, encode(before), "run"));
        if (slots == null) {
            return merged;
        }
        if (before.tools() == null || count == null) {
            throw new IllegalArgumentException("tool slot changes refer to a tool round that is not in the file");
        }
        List<ToolSlot> round = new ArrayList<>(before.tools().slots());
        for (int i = 0; i < count.asInt(); i++) {
            JsonNode slot = slots.get(Integer.toString(i));
            if (slot != null) {
                ToolSlot value = mapper.treeToValue(slot, ToolSlot.class);
                if (i < round.size()) {
                    round.set(i, value);
                } else {
                    round.add(value);
                }
            } else if (i >= round.size()) {
                throw new IllegalArgumentException("tool slot " + i + " is neither earlier in the file nor in this change");
            }
        }
        return merged.withTools(new ToolRound(round, checked == null ? before.tools().checked() : checked.asInt()));
    }
}
