package tantalum.shell.journal;

import java.util.ArrayList;
import java.util.List;
import tantalum.value.state.RunState;
import tantalum.value.state.ToolRound;
import tantalum.value.state.ToolSlot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 结果行里运行的变化（从 {@code RecordCodec} 拆出）。工具轮每完成一个调用就变一次，其中只有一格变了：
 * 两边都有工具轮时不写整个 {@code tools}，写 {@code toolSlots}（变了的格，按下标）和 {@code toolCount}、{@code toolsChecked}。
 * 这些名字不是 {@code RunState} 的字段，不会混淆。
 */
final class RunDelta {

    private final JsonMapper mapper;
    private final Deltas deltas;

    RunDelta(JsonMapper mapper, Deltas deltas) {
        this.mapper = mapper;
        this.deltas = deltas;
    }

    ObjectNode encode(RunState now, RunState before) {
        ObjectNode delta = deltas.delta(mapper.valueToTree(now), mapper.valueToTree(before), "ref");
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
        RunState merged = mapper.treeToValue(deltas.merge(delta, before, "run"), RunState.class);
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
